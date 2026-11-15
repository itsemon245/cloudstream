package com.lagradost.cloudstream3.ui.browse

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePageCache
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSort
import com.lagradost.cloudstream3.ui.browse.engine.ClientFilters
import com.lagradost.cloudstream3.ui.browse.engine.SourceStatus
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class BrowseViewModelTest {
    private lateinit var main: ExecutorCoroutineDispatcher

    @Before fun setUp() {
        main = newSingleThreadContext("test-main")
        Dispatchers.setMain(main)
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        BrowsePageCache.shared.clear()
    }

    @After fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
        Dispatchers.resetMain()
        main.close()
        BrowsePageCache.shared.clear()
    }

    private fun waitFor(vm: BrowseViewModel, condition: (BrowseUiState) -> Boolean): BrowseUiState = runBlocking {
        withTimeout(5_000) {
            while (true) {
                val state = withContext(main) { vm.state.value }
                if (state != null && state.progress == null && condition(state)) return@withTimeout state
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private fun onMain(block: () -> Unit) = runBlocking { withContext(main) { block() } }

    private val a = FakeSource(
        "A", listOf("Trending", "Action Movies", "Horror"),
        pages = mapOf(
            1 to listOf(listOf(movie("A", "Alpha", 2020), series("A", "Bravo", 2010), movie("A", "Charlie", 2024))),
            2 to listOf(listOf(movie("A", "Scream", 1996))),
        ),
    )
    private val b = FakeSource("B", listOf("Latest"))

    @Test fun clientFiltersNeverRefetchButGenreChangesDo() {
        val vm = BrowseViewModel()
        onMain { vm.init(listOf(a, b), setOf("action"), ClientFilters(), emptySet()) }

        var state = waitFor(vm) { it.items.size == 3 }
        assertEquals(1, a.calls.get())
        assertEquals(0, b.calls.get())
        assertTrue(state.statuses.first { it.name == "B" } is SourceStatus.Skipped)

        onMain { vm.onEvent(BrowseEvent.SetQuery("char")) }
        state = waitFor(vm) { it.items.size == 1 }
        assertEquals("Charlie", state.items.single().name)

        onMain { vm.onEvent(BrowseEvent.SetQuery("")) }
        onMain { vm.onEvent(BrowseEvent.SetSort(BrowseSort.Newest)) }
        state = waitFor(vm) { it.items.size == 3 && it.filters.sort == BrowseSort.Newest }
        assertEquals(listOf("Charlie", "Alpha", "Bravo"), state.items.map { it.name })

        // Type change re-plans but every page comes from the cache.
        onMain { vm.onEvent(BrowseEvent.SetTypes(setOf(TvType.TvSeries))) }
        state = waitFor(vm) { it.items.size == 1 }
        assertEquals("Bravo", state.items.single().name)
        assertEquals(1, a.calls.get())

        // A new genre needs new pages.
        onMain { vm.onEvent(BrowseEvent.SetTypes(emptySet())) }
        onMain { vm.onEvent(BrowseEvent.SetGenres(setOf("horror"))) }
        state = waitFor(vm) { it.items.map { r -> r.name } == listOf("Scream") }
        assertEquals(2, a.calls.get())
    }

    @Test fun reportsWhenNoSourceHasTheGenre() {
        val vm = BrowseViewModel()
        onMain { vm.init(listOf(a, b), setOf("western"), ClientFilters(), emptySet()) }
        val state = waitFor(vm) { it.noMatchingSource }
        assertTrue(state.items.isEmpty())
        assertEquals(0, a.calls.get())
    }

    @Test fun previewCountOnlyWhenNoFetchNeeded() {
        val vm = BrowseViewModel()
        onMain { vm.init(listOf(a), setOf("action"), ClientFilters(), emptySet()) }
        waitFor(vm) { it.items.size == 3 }
        runBlocking {
            withContext(main) {
                assertEquals(2, vm.previewCount(setOf("action"), ClientFilters(yearFrom = 2015, hideUnknown = true), emptySet()))
                assertEquals(null, vm.previewCount(setOf("horror"), ClientFilters(), emptySet()))
            }
        }
    }

    private fun settle(ms: Long = 300) = runBlocking { delay(ms) }

    /** T1 + V2: auto-fill stops after MAX_AUTO_STEPS, and a manual load doesn't reset it. */
    @Test fun autoFillIsBoundedAndManualLoadDoesNotResetIt() {
        val pages = (1..10).map { p -> listOf(movie("R", "Low $p", score = 5.0)) }
        val r = FakeSource("R", listOf("Action"), pages = mapOf(0 to pages))
        val vm = BrowseViewModel()
        onMain { vm.init(listOf(r), setOf("action"), ClientFilters(minRating = 7.0), emptySet()) }

        waitFor(vm) { it.loadedCount == 1 + BrowseViewModel.MAX_AUTO_STEPS }
        settle()
        assertEquals(1 + BrowseViewModel.MAX_AUTO_STEPS, r.calls.get())
        assertTrue(waitFor(vm) { true }.items.isEmpty())

        // The user scrolls: one more page, and the screen-fill fallback stays exhausted.
        onMain { vm.onEvent(BrowseEvent.LoadMore) }
        waitFor(vm) { it.loadedCount == 2 + BrowseViewModel.MAX_AUTO_STEPS }
        onMain { vm.onEvent(BrowseEvent.FillScreen) }
        settle()
        assertEquals(2 + BrowseViewModel.MAX_AUTO_STEPS, r.calls.get())
    }

    /** T2: switching genre mid-load cancels the old request and drops its results. */
    @Test fun genreChangeMidLoadDropsStaleResults() {
        val slow = FakeSource("Slow", listOf("Action"), mapOf(0 to listOf(listOf(movie("Slow", "Stale")))), latencyMs = 1_000)
        val fast = FakeSource("Fast", listOf("Comedy"), mapOf(0 to listOf(listOf(movie("Fast", "Fresh")))))
        val vm = BrowseViewModel()
        onMain { vm.init(listOf(slow, fast), setOf("action"), ClientFilters(), emptySet()) }
        runBlocking { withTimeout(5_000) { while (slow.calls.get() == 0) delay(5) } }
        onMain { vm.onEvent(BrowseEvent.SetGenres(setOf("comedy"))) }

        waitFor(vm) { it.items.map { r -> r.name } == listOf("Fresh") }
        settle(1_300)
        val state = waitFor(vm) { true }
        assertEquals(listOf("Fresh"), state.items.map { it.name })
        assertEquals(0, slow.completed.get())
    }

    /** T3: applying the sheet retries a selected source that failed. */
    @Test fun applyRetriesFailedSources() {
        val flaky = FakeSource("Flaky", listOf("Action"), mapOf(0 to listOf(listOf(movie("Flaky", "Back")))), failFirstCalls = 1)
        val vm = BrowseViewModel()
        onMain { vm.init(listOf(flaky), setOf("action"), ClientFilters(), emptySet()) }
        var state = waitFor(vm) { s -> s.statuses.any { it is SourceStatus.Failed } }
        assertEquals(1, flaky.calls.get())

        onMain { vm.onEvent(BrowseEvent.Apply(state.genres, state.filters, emptySet())) }
        state = waitFor(vm) { it.items.isNotEmpty() }
        assertEquals(2, flaky.calls.get())
        assertTrue(state.statuses.single() is SourceStatus.Loaded)
        assertEquals("Back", state.items.single().name)
    }
}
