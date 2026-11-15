package com.lagradost.cloudstream3.ui.browse.engine

/**
 * State of one fetch ([FetchKey]): which sections are planned, which page each is on, per-source
 * status and the aggregated pool. Pure bookkeeping; the view model feeds it [FetchEvent]s.
 */
class BrowseSession(
    sources: List<BrowseSource>,
    val key: FetchKey,
    maxItems: Int = 3000,
) {
    val plans: List<SourcePlan> = BrowsePlanner.plan(sources, key)
    val aggregator = BrowseAggregator(maxItems)

    private class SectionState(var loadedPages: Int = 0, var hasNext: Boolean = true, var inFlight: Boolean = false)

    private val sections = LinkedHashMap<Pair<String, Int>, SectionState>()
    private val failures = HashMap<String, String?>()
    private val titleCounts = HashMap<String, Int>()

    /** Requests of the current step and how many of them finished; for the progress bar. */
    var stepTotal = 0
        private set
    var stepDone = 0
        private set

    init {
        for (plan in plans) if (plan is SourcePlan.Fetch) {
            for (index in plan.sections.keys) sections[plan.source.name to index] = SectionState()
        }
    }

    val fetchPlans: List<SourcePlan.Fetch> get() = plans.filterIsInstance<SourcePlan.Fetch>()

    val isLoading: Boolean get() = sections.values.any { it.inFlight }

    val canLoadMore: Boolean
        get() = !aggregator.isFull && sections.values.any { it.hasNext && !it.inFlight }

    /** Page 1 of every planned section. */
    fun firstRequests(): List<PageRequest> = nextRequests()

    /** The next page of every section that has one and is idle. Marks them in flight. */
    fun nextRequests(): List<PageRequest> {
        if (aggregator.isFull) return emptyList()
        val requests = ArrayList<PageRequest>()
        // Interleave providers: first section of each provider, then the second, ...
        val perPlan = fetchPlans.map { plan ->
            plan.sections.keys.mapNotNull { index ->
                val state = sections[plan.source.name to index] ?: return@mapNotNull null
                if (!state.hasNext || state.inFlight) null
                else PageRequest(plan, index, state.loadedPages + 1)
            }
        }
        val longest = perPlan.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (list in perPlan) list.getOrNull(i)?.let { requests += it }
        for (request in requests) sections[request.sourceName to request.sectionIndex]?.inFlight = true
        stepTotal = requests.size
        stepDone = 0
        return requests
    }

    /** @return number of new titles. */
    fun onEvent(event: FetchEvent): Int {
        val request = event.request
        val state = sections[request.sourceName to request.sectionIndex] ?: return 0
        state.inFlight = false
        stepDone++
        return when (event) {
            is FetchEvent.PageLoaded -> {
                state.loadedPages = request.page
                state.hasNext = event.page.hasNext && event.page.items.isNotEmpty()
                failures.remove(request.sourceName)
                val added = aggregator.add(
                    sourceName = request.sourceName,
                    providerOrder = request.plan.order,
                    supportedTypes = request.plan.source.supportedTypes,
                    sectionGenres = request.genres,
                    responses = event.page.items,
                )
                titleCounts[request.sourceName] = (titleCounts[request.sourceName] ?: 0) + added
                added
            }

            is FetchEvent.PageFailed -> {
                state.hasNext = false
                failures[request.sourceName] = event.error.message
                0
            }
        }
    }

    /** Cancelled requests go back to idle so they are requested again next time. */
    fun resetInFlight() {
        for (state in sections.values) state.inFlight = false
    }

    /** Re-enables failed sections of [sourceName] (retry). */
    fun retry(sourceName: String) {
        failures.remove(sourceName)
        for ((k, state) in sections) if (k.first == sourceName && state.loadedPages == 0) state.hasNext = true
    }

    fun statuses(): List<SourceStatus> = plans.map { plan ->
        when (plan) {
            is SourcePlan.Skipped -> SourceStatus.Skipped(plan.source.name, plan.reason)
            is SourcePlan.Fetch -> {
                val name = plan.source.name
                val own = sections.filterKeys { it.first == name }.values
                when {
                    own.any { it.inFlight } -> SourceStatus.Loading(name, own.size)
                    failures.containsKey(name) && (titleCounts[name] ?: 0) == 0 ->
                        SourceStatus.Failed(name, own.size, failures[name])

                    else -> SourceStatus.Loaded(name, own.size, titleCounts[name] ?: 0)
                }
            }
        }
    }
}
