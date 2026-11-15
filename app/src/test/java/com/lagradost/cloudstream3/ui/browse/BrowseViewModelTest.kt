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
}
