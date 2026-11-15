package com.lagradost.cloudstream3.ui.browse

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePageCache
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSort
import com.lagradost.cloudstream3.ui.browse.engine.ClientFilters
import com.lagradost.cloudstream3.ui.browse.engine.GenreIndex
import com.lagradost.cloudstream3.ui.browse.engine.SourceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * View model tests on virtual time: Main, provider requests, index building and filtering all
 * run on one [StandardTestDispatcher], so every assertion follows [advanceUntilIdle] and nothing
 * depends on real thread timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
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
        BrowsePageCache.shared.clear()
    }

    private fun newViewModel() = BrowseViewModel(ioDispatcher = dispatcher, workDispatcher = dispatcher)

    /** Runs everything pending, then returns a state whose items match its filters. */
    private fun TestScope.settled(vm: BrowseViewModel): BrowseUiState {
        advanceUntilIdle()
        val state = vm.state.value!!
        assertNull("still loading", state.progress)
        assertTrue("items computed from older filters", state.itemsUpToDate)
        return state
    }

    private val a = FakeSource(
        "A", listOf("Trending", "Action Movies", "Horror"),
        pages = mapOf(
            1 to listOf(listOf(movie("A", "Alpha", 2020), series("A", "Bravo", 2010), movie("A", "Charlie", 2024))),
            2 to listOf(listOf(movie("A", "Scream", 1996))),
        ),
    )
    private val b = FakeSource("B", listOf("Latest"))

    @Test fun clientFiltersNeverRefetchButGenreChangesDo() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.init(listOf(a, b), setOf("action"), ClientFilters(), emptySet())

        var state = settled(vm)
        assertEquals(3, state.items.size)
        assertEquals(1, a.calls.get())
        assertEquals(0, b.calls.get())
        assertTrue(state.statuses.first { it.name == "B" } is SourceStatus.Skipped)

        vm.onEvent(BrowseEvent.SetQuery("char"))
        state = settled(vm)
        assertEquals(listOf("Charlie"), state.items.map { it.name })

        // Back-to-back events: the list must reflect the last one, not an earlier recompute.
        vm.onEvent(BrowseEvent.SetQuery(""))
        vm.onEvent(BrowseEvent.SetSort(BrowseSort.Newest))
        state = settled(vm)
        assertEquals(listOf("Charlie", "Alpha", "Bravo"), state.items.map { it.name })

        // Type change re-plans but every page comes from the cache.
        vm.onEvent(BrowseEvent.SetTypes(setOf(TvType.TvSeries)))
        state = settled(vm)
        assertEquals(listOf("Bravo"), state.items.map { it.name })
        assertEquals(1, a.calls.get())

        // A new genre needs new pages.
        vm.onEvent(BrowseEvent.SetTypes(emptySet()))
        vm.onEvent(BrowseEvent.SetGenres(setOf("horror")))
        state = settled(vm)
        assertEquals(listOf("Scream"), state.items.map { it.name })
        assertEquals(2, a.calls.get())
    }

    /** The genre index finishing late must not start a second fetch for the same key. */
    @Test fun slowIndexBuildDoesNotRefetch() = runTest(dispatcher) {
        val vm = newViewModel()
        var indexBuilds = 0
        vm.init(listOf(a, b), setOf("action"), ClientFilters(), emptySet()) { sources ->
            indexBuilds++
            GenreIndex.build(sources)
        }
        // These run before the index coroutine (nothing has been dispatched yet): one changes
        // the fetch key, one only re-filters.
        vm.onEvent(BrowseEvent.SetTypes(setOf(TvType.Movie)))
        vm.onEvent(BrowseEvent.SetSort(BrowseSort.NameAsc))
        val state = settled(vm)
        assertEquals(listOf("Alpha", "Charlie"), state.items.map { it.name })
        assertEquals(1, indexBuilds)
        assertEquals(1, a.calls.get())
    }

    @Test fun eventsBeforeIndexAreNotOverwritten() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.init(listOf(a, b), emptySet(), ClientFilters(), emptySet())
        vm.onEvent(BrowseEvent.SetGenres(setOf("horror")))
        val state = settled(vm)
        assertEquals(setOf("horror"), state.genres)
        assertEquals(listOf("Scream"), state.items.map { it.name })
        assertEquals(1, a.calls.get())
    }

    @Test fun reportsWhenNoSourceHasTheGenre() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.init(listOf(a, b), setOf("western"), ClientFilters(), emptySet())
        val state = settled(vm)
        assertTrue(state.noMatchingSource)
        assertTrue(state.items.isEmpty())
        assertEquals(0, a.calls.get())
    }

    @Test fun previewCountOnlyWhenNoFetchNeeded() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.init(listOf(a), setOf("action"), ClientFilters(), emptySet())
        assertEquals(3, settled(vm).items.size)
        assertEquals(2, vm.previewCount(setOf("action"), ClientFilters(yearFrom = 2015, hideUnknown = true), emptySet()))
        assertEquals(null, vm.previewCount(setOf("horror"), ClientFilters(), emptySet()))
    }

    /** T1 + V2: auto-fill stops after MAX_AUTO_STEPS, and a manual load doesn't reset it. */
    @Test fun autoFillIsBoundedAndManualLoadDoesNotResetIt() = runTest(dispatcher) {
        val pages = (1..10).map { p -> listOf(movie("R", "Low $p", score = 5.0)) }
        val r = FakeSource("R", listOf("Action"), pages = mapOf(0 to pages))
        val vm = newViewModel()
        vm.init(listOf(r), setOf("action"), ClientFilters(minRating = 7.0), emptySet())

        var state = settled(vm)
        assertEquals(1 + BrowseViewModel.MAX_AUTO_STEPS, r.calls.get())
        assertEquals(1 + BrowseViewModel.MAX_AUTO_STEPS, state.loadedCount)
        assertTrue(state.items.isEmpty())
        assertTrue(state.canLoadMore)

        // The user scrolls: one more page, and the screen-fill fallback stays exhausted.
        vm.onEvent(BrowseEvent.LoadMore)
        state = settled(vm)
        assertEquals(2 + BrowseViewModel.MAX_AUTO_STEPS, state.loadedCount)
        vm.onEvent(BrowseEvent.FillScreen)
        settled(vm)
        assertEquals(2 + BrowseViewModel.MAX_AUTO_STEPS, r.calls.get())
    }

    /** T2: switching genre mid-load cancels the old request and drops its results. */
    @Test fun genreChangeMidLoadDropsStaleResults() = runTest(dispatcher) {
        val slow = FakeSource("Slow", listOf("Action"), mapOf(0 to listOf(listOf(movie("Slow", "Stale")))), latencyMs = 1_000)
        val fast = FakeSource("Fast", listOf("Comedy"), mapOf(0 to listOf(listOf(movie("Fast", "Fresh")))))
        val vm = newViewModel()
        vm.init(listOf(slow, fast), setOf("action"), ClientFilters(), emptySet())
        advanceTimeBy(100); runCurrent()
        assertEquals(1, slow.calls.get())

        vm.onEvent(BrowseEvent.SetGenres(setOf("comedy")))
        val state = settled(vm)
        assertEquals(listOf("Fresh"), state.items.map { it.name })
        assertEquals(0, slow.completed.get())
    }

    /** T3: applying the sheet retries a selected source that failed. */
    @Test fun applyRetriesFailedSources() = runTest(dispatcher) {
        val flaky = FakeSource("Flaky", listOf("Action"), mapOf(0 to listOf(listOf(movie("Flaky", "Back")))), failFirstCalls = 1)
        val vm = newViewModel()
        vm.init(listOf(flaky), setOf("action"), ClientFilters(), emptySet())
        var state = settled(vm)
        assertTrue(state.statuses.single() is SourceStatus.Failed)
        assertEquals(1, flaky.calls.get())

        vm.onEvent(BrowseEvent.Apply(state.genres, state.filters, emptySet()))
        state = settled(vm)
        assertEquals(2, flaky.calls.get())
        assertTrue(state.statuses.single() is SourceStatus.Loaded)
        assertEquals(listOf("Back"), state.items.map { it.name })
    }
}
