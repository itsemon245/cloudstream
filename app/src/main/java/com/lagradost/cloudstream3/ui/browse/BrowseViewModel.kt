package com.lagradost.cloudstream3.ui.browse

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowseFetcher
import com.lagradost.cloudstream3.ui.browse.engine.BrowseFilter
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePageCache
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePlanner
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSession
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSort
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSource
import com.lagradost.cloudstream3.ui.browse.engine.ClientFilters
import com.lagradost.cloudstream3.ui.browse.engine.FetchKey
import com.lagradost.cloudstream3.ui.browse.engine.GenreMatcher
import com.lagradost.cloudstream3.ui.browse.engine.PageRequest
import com.lagradost.cloudstream3.ui.browse.engine.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GenreOption(val key: String, val name: String, val sources: Int)

data class BrowseUiState(
    val genres: Set<String> = emptySet(),
    val availableGenres: List<GenreOption> = emptyList(),
    val filters: ClientFilters = ClientFilters(),
    /** Empty = all installed. */
    val chosenSources: Set<String> = emptySet(),
    val allSources: List<String> = emptyList(),
    val items: List<SearchResponse> = emptyList(),
    val loadedCount: Int = 0,
    /** 0..1 while loading, null when idle. */
    val progress: Float? = null,
    val canLoadMore: Boolean = false,
    val statuses: List<SourceStatus> = emptyList(),
    /** True when no selected source has a section for the selected genre(s). */
    val noMatchingSource: Boolean = false,
) {
    val genreLabel: String
        get() = genres.joinToString(", ") { GenreMatcher.displayName(it) }
}

sealed class BrowseEvent {
    data class SetGenres(val genres: Set<String>) : BrowseEvent()
    data class SetTypes(val types: Set<TvType>) : BrowseEvent()
    data class SetSort(val sort: BrowseSort) : BrowseEvent()
    data class SetQuery(val query: String) : BrowseEvent()
    /** Everything from the filter sheet at once. */
    data class Apply(val genres: Set<String>, val filters: ClientFilters, val sources: Set<String>) : BrowseEvent()
    data class SetSources(val sources: Set<String>) : BrowseEvent()
    data object LoadMore : BrowseEvent()
    data class Retry(val source: String) : BrowseEvent()
}

/**
 * MVI-style view model: a single [BrowseUiState] and one [onEvent] entry point.
 *
 * Only changes to genres, sources or types start a fetch (cached pages are reused); every other
 * filter, the sort and the in-page search only re-filter the titles already loaded.
 */
class BrowseViewModel : ViewModel() {
    companion object {
        /** Keep loading pages automatically until this many titles are visible. */
        const val AUTO_FILL_TARGET = 30
        const val MAX_AUTO_STEPS = 3
    }

    private val _state = MutableLiveData(BrowseUiState())
    val state: LiveData<BrowseUiState> get() = _state
    private val current: BrowseUiState get() = _state.value ?: BrowseUiState()

    private val fetcher = BrowseFetcher(BrowsePageCache.shared)
    private var sources: List<BrowseSource> = emptyList()
    private var session: BrowseSession? = null
    private var sessionJob: Job? = null
    private var stepJob: Job? = null
    private var filterJob: Job? = null
    private var autoSteps = 0

    val isInitialized: Boolean get() = session != null

    /** Providers Browse can use (after the user's language / media settings). */
    val sourceList: List<BrowseSource> get() = sources

    /**
     * How many loaded titles the given sheet settings would show, or null when they need a new
     * fetch (different genres, sources or types), in which case the count is not known yet.
     */
    fun previewCount(genres: Set<String>, filters: ClientFilters, chosenSources: Set<String>): Int? {
        val s = session ?: return null
        if (FetchKey(genres, chosenSources, filters.types) != s.key) return null
        val withQuery = filters.copy(query = current.filters.query)
        return s.aggregator.snapshot().count { BrowseFilter.matches(it, withQuery, genres) }
    }

    /** Called by the fragment with the providers allowed by the user's settings. */
    fun init(
        sources: List<BrowseSource>,
        genres: Set<String>,
        filters: ClientFilters,
        chosenSources: Set<String>,
    ) {
        this.sources = sources
        val available = BrowsePlanner.availableGenres(sources).map { (key, count) ->
            GenreOption(key, GenreMatcher.displayName(key), count)
        }
        val validChosen = chosenSources.filterTo(HashSet()) { name -> sources.any { it.name == name } }
        val startGenres = genres.ifEmpty { setOfNotNull(available.firstOrNull()?.key) }
        _state.value = current.copy(
            genres = startGenres,
            availableGenres = available,
            filters = filters,
            chosenSources = validChosen,
            allSources = sources.map { it.name },
        )
        restartFetch()
    }

    fun onEvent(event: BrowseEvent) {
        val state = current
        when (event) {
            is BrowseEvent.SetGenres -> if (event.genres.isNotEmpty() && event.genres != state.genres) {
                _state.value = state.copy(genres = event.genres)
                restartFetch()
            }

            is BrowseEvent.SetTypes -> {
                _state.value = state.copy(filters = state.filters.copy(types = event.types))
                if (event.types != state.filters.types) restartFetch() else refilter()
            }

            is BrowseEvent.SetSort -> {
                _state.value = state.copy(filters = state.filters.copy(sort = event.sort))
                refilter()
            }

            is BrowseEvent.SetQuery -> if (event.query != state.filters.query) {
                _state.value = state.copy(filters = state.filters.copy(query = event.query))
                refilter()
            }

            is BrowseEvent.Apply -> {
                val genres = event.genres.ifEmpty { state.genres }
                val newState = state.copy(
                    genres = genres,
                    filters = event.filters.copy(query = state.filters.query),
                    chosenSources = event.sources,
                )
                _state.value = newState
                if (fetchKey(newState) != session?.key) restartFetch() else refilter()
            }

            is BrowseEvent.SetSources -> if (event.sources != state.chosenSources) {
                _state.value = state.copy(chosenSources = event.sources)
                restartFetch()
            }

            BrowseEvent.LoadMore -> loadMore(auto = false)
            is BrowseEvent.Retry -> {
                session?.retry(event.source)
                loadMore(auto = false)
            }
        }
    }

    private fun fetchKey(state: BrowseUiState) =
        FetchKey(state.genres, state.chosenSources, state.filters.types)

    private fun restartFetch() {
        sessionJob?.cancel()
        stepJob = null
        autoSteps = 0
        val newSession = BrowseSession(sources, fetchKey(current))
        session = newSession
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        val requests = newSession.firstRequests()
        publishSession(newSession)
        runStep(newSession, requests)
    }

    private fun loadMore(auto: Boolean) {
        val s = session ?: return
        if (stepJob?.isActive == true) return
        if (!auto) autoSteps = 0
        val requests = s.nextRequests()
        if (requests.isEmpty()) return
        runStep(s, requests)
    }

    private fun runStep(s: BrowseSession, requests: List<PageRequest>) {
        if (requests.isEmpty()) {
            refilter()
            return
        }
        val parent = sessionJob ?: return
        stepJob = viewModelScope.launch(parent) {
            try {
                fetcher.fetch(requests).flowOn(Dispatchers.IO).collect { event ->
                    if (session !== s) return@collect
                    s.onEvent(event)
                    publishSession(s)
                }
            } catch (e: CancellationException) {
                s.resetInFlight()
                throw e
            }
            if (session !== s) return@launch
            publishSession(s)
            // Wait for the filtered list so the auto-fill decision sees the real count.
            filterJob?.join()
            stepJob = null
            maybeAutoFill(s)
        }
    }

    /** Fill the screen when strict filters leave too few titles, but never run away. */
    private fun maybeAutoFill(s: BrowseSession) {
        val visible = current.items.size
        if (visible < AUTO_FILL_TARGET && s.canLoadMore && autoSteps < MAX_AUTO_STEPS) {
            autoSteps++
            loadMore(auto = true)
        }
    }

    private fun publishSession(s: BrowseSession) {
        val progress = if (s.isLoading && s.stepTotal > 0) s.stepDone.toFloat() / s.stepTotal else null
        _state.value = current.copy(
            loadedCount = s.aggregator.size,
            progress = progress,
            canLoadMore = s.canLoadMore,
            statuses = s.statuses(),
            noMatchingSource = s.fetchPlans.isEmpty(),
        )
        refilter()
    }

    /** Re-filters the loaded pool off the main thread; only the latest request wins. */
    private fun refilter() {
        val s = session ?: return
        val pool = s.aggregator.snapshot()
        val state = current
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                BrowseFilter.apply(pool, state.filters, state.genres).map { it.response }
            }
            _state.value = current.copy(items = result)
        }
    }
}
