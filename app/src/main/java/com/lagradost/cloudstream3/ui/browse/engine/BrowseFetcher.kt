package com.lagradost.cloudstream3.ui.browse.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class PageRequest(
    val plan: SourcePlan.Fetch,
    val sectionIndex: Int,
    val page: Int,
    /** Page of an excluded-genre section: its titles are hidden, not shown. */
    val exclusion: Boolean = false,
) {
    val sourceName: String get() = plan.source.name
    val genres: Set<String> get() = plan.sections[sectionIndex].orEmpty()
}

sealed class FetchEvent {
    abstract val request: PageRequest

    data class PageLoaded(
        override val request: PageRequest,
        val page: SectionPage,
        val fromCache: Boolean,
    ) : FetchEvent()

    data class PageFailed(override val request: PageRequest, val error: Throwable) : FetchEvent()
}

/**
 * Loads section pages with bounded concurrency:
 * * at most [maxConcurrent] provider requests in flight overall,
 * * at most one in flight per provider, with the provider's own delay between its requests,
 * * cached pages are emitted immediately without a request,
 * * results are emitted as soon as each page arrives (progressive loading),
 * * cancelling the collector cancels every in-flight request.
 */
class BrowseFetcher(
    private val cache: BrowsePageCache,
    private val maxConcurrent: Int = 4,
) {
    fun fetch(requests: List<PageRequest>): Flow<FetchEvent> = channelFlow {
        val semaphore = Semaphore(maxConcurrent)
        for (perSource in requests.groupBy { it.sourceName }.values) {
            launch {
                var requested = false
                for (request in perSource) {
                    val source = request.plan.source
                    val key = BrowsePageCache.Key(
                        source.name,
                        source.sectionNames.getOrElse(request.sectionIndex) { "" },
                        request.sectionIndex,
                        request.page,
                    )
                    val cached = cache.get(key)
                    if (cached != null) {
                        send(FetchEvent.PageLoaded(request, cached, fromCache = true))
                        continue
                    }
                    if (requested && source.requestDelayMs > 0) delay(source.requestDelayMs)
                    requested = true
                    val event = semaphore.withPermit {
                        try {
                            val page = source.loadSection(request.sectionIndex, request.page)
                            cache.put(key, page)
                            FetchEvent.PageLoaded(request, page, fromCache = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            FetchEvent.PageFailed(request, t)
                        }
                    }
                    send(event)
                }
            }
        }
    }
}
