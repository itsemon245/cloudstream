package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.ui.browse.engine.BrowseFetcher
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePageCache
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSession
import com.lagradost.cloudstream3.ui.browse.engine.FetchEvent
import com.lagradost.cloudstream3.ui.browse.engine.FetchKey
import com.lagradost.cloudstream3.ui.browse.engine.SourceStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseFetcherTest {
    private fun pages(source: String, prefix: String, count: Int, perPage: Int = 2) =
        (0 until count).map { p -> (0 until perPage).map { i -> movie(source, "$prefix-$p-$i") } }

    @Test fun boundedConcurrencyAndOneRequestPerProvider() = runTest {
        val inFlight = AtomicInteger()
        val max = AtomicInteger()
        val sources = (1..8).map { n ->
            FakeSource(
                "S$n", listOf("Action", "Action Classics"),
                pages = mapOf(0 to pages("S$n", "a", 1), 1 to pages("S$n", "b", 1)),
                latencyMs = 100, inFlightCounter = inFlight, maxInFlight = max,
            )
        }
        val session = BrowseSession(sources, FetchKey(setOf("action")))
        val events = BrowseFetcher(BrowsePageCache(), maxConcurrent = 4)
            .fetch(session.firstRequests()).toList()

        assertEquals(16, events.size)
        assertEquals(4, max.get())
        sources.forEach { assertEquals(1, it.maxOwnInFlight.get()) }
    }

    @Test fun emitsProgressivelyFastSourcesFirst() = runTest {
        val slow = FakeSource("Slow", listOf("Action"), mapOf(0 to pages("Slow", "s", 1)), latencyMs = 1000)
        val fast = FakeSource("Fast", listOf("Action"), mapOf(0 to pages("Fast", "f", 1)), latencyMs = 10)
        val session = BrowseSession(listOf(slow, fast), FetchKey(setOf("action")))
        val events = BrowseFetcher(BrowsePageCache()).fetch(session.firstRequests()).toList()
        assertEquals(listOf("Fast", "Slow"), events.map { it.request.sourceName })
    }

    @Test fun cachedPagesDoNotHitProviders() = runTest {
        val source = FakeSource("A", listOf("Action"), mapOf(0 to pages("A", "a", 2)))
        val cache = BrowsePageCache()
        val fetcher = BrowseFetcher(cache)

        fetcher.fetch(BrowseSession(listOf(source), FetchKey(setOf("action"))).firstRequests()).toList()
        assertEquals(1, source.calls.get())

        // A new fetch for the same genre (e.g. after a type filter change) reuses the cache.
        val again = fetcher.fetch(BrowseSession(listOf(source), FetchKey(setOf("action"))).firstRequests()).toList()
        assertEquals(1, source.calls.get())
        assertTrue((again.single() as FetchEvent.PageLoaded).fromCache)
    }

    @Test fun cancellingStopsInFlightRequests() = runTest {
        val inFlight = AtomicInteger()
        val sources = (1..3).map {
            FakeSource("S$it", listOf("Action"), mapOf(0 to pages("S$it", "a", 1)), latencyMs = 1000, inFlightCounter = inFlight)
        }
        val received = mutableListOf<FetchEvent>()
        val job = launch {
            BrowseFetcher(BrowsePageCache()).fetch(BrowseSession(sources, FetchKey(setOf("action"))).firstRequests())
                .collect { received += it }
        }
        advanceTimeBy(500); runCurrent()
        assertEquals(3, inFlight.get())
        job.cancel(); job.join()
        assertEquals(0, inFlight.get())
        assertTrue(received.isEmpty())
    }

    @Test fun providerDelayIsRespectedBetweenItsRequests() = runTest {
        val source = FakeSource("Seq", listOf("Action", "More Action"),
            mapOf(0 to pages("Seq", "a", 1), 1 to pages("Seq", "b", 1)), requestDelayMs = 5000)
        val start = testScheduler.currentTime
        BrowseFetcher(BrowsePageCache()).fetch(BrowseSession(listOf(source), FetchKey(setOf("action"))).firstRequests()).toList()
        assertEquals(5000, testScheduler.currentTime - start)
    }

    @Test fun sessionPagesUntilProvidersRunOutAndReportsFailures() = runTest {
        val ok = FakeSource("OK", listOf("Action"), mapOf(0 to pages("OK", "a", 2)))
        val bad = FakeSource("Bad", listOf("Action"), failSections = setOf(0))
        val none = FakeSource("None", listOf("Latest"))
        val session = BrowseSession(listOf(ok, bad, none), FetchKey(setOf("action")))
        val fetcher = BrowseFetcher(BrowsePageCache())

        fetcher.fetch(session.firstRequests()).collect { session.onEvent(it) }
        assertEquals(2, session.aggregator.size)
        assertTrue(session.canLoadMore)
        val statuses = session.statuses().associateBy { it.name }
        assertTrue(statuses["OK"] is SourceStatus.Loaded)
        assertTrue(statuses["Bad"] is SourceStatus.Failed)
        assertTrue(statuses["None"] is SourceStatus.Skipped)

        val next = session.nextRequests()
        assertEquals(listOf(2), next.map { it.page })
        fetcher.fetch(next).collect { session.onEvent(it) }
        assertEquals(4, session.aggregator.size)
        assertFalse(session.canLoadMore)
        assertTrue(session.nextRequests().isEmpty())
    }
}
