package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowseAggregator
import com.lagradost.cloudstream3.ui.browse.engine.BrowseFilter
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePaging
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePlanner
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSort
import com.lagradost.cloudstream3.ui.browse.engine.ClientFilters
import com.lagradost.cloudstream3.ui.browse.engine.FetchKey
import com.lagradost.cloudstream3.ui.browse.engine.GenreIndex
import com.lagradost.cloudstream3.ui.browse.engine.GenreMatcher
import com.lagradost.cloudstream3.ui.browse.engine.SourcePlan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreIndexTest {
    @Test fun discoversGenresOnlyWhenTwoSourcesShareThem() {
        val a = FakeSource("A", listOf("Latest Movies", "Trending Now", "Superhero Movies", "Martial Arts", "Action"))
        val b = FakeSource("B", listOf("Popular", "Superhero", "New Releases", "Isekai"))
        val c = FakeSource("C", listOf("Top Rated", "Recently Added", "Martial Arts Films"))
        val available = GenreIndex.build(listOf(a, b, c)).available.toMap()

        assertEquals(2, available["superhero"])
        assertEquals(2, available["martial arts"])
        assertEquals(1, available["action"])  // canonical genres need only one source
        assertFalse("isekai" in available)     // only one source
        // List-style names never become genres.
        assertTrue(available.keys.none { it in setOf("latest", "trending", "popular", "new", "recent", "top", "rated") })
    }

    @Test fun discoveredKeyIgnoresListNamesAndCanonicalGenres() {
        fun key(name: String) = GenreMatcher.discoveredKeyOf(GenreMatcher.tokens(name))
        assertNull(key("Latest Episodes"))
        assertNull(key("Trending This Week"))
        assertNull(key("Action Movies"))
        assertNull(key("2024 Releases"))
        assertNull(key("One Two Three Four Words"))
        assertEquals("superhero", key("Top Superhero Movies"))
    }

    @Test fun indexPlansLikeDirectMatching() {
        val sources = listOf(
            FakeSource("A", listOf("Trending", "Action Movies", "Horror", "Action & Adventure", "Superhero Movies")),
            FakeSource("B", listOf("Superhero & Villains", "Comedy")),
            FakeSource("C", listOf("Neo Noir Picks"), hasMainPage = true),
        )
        val index = GenreIndex.build(sources)
        for (genres in listOf(setOf("action"), setOf("horror", "comedy"), setOf("superhero"), setOf("neo noir"))) {
            val key = FetchKey(genres)
            val indexed = BrowsePlanner.plan(sources, key, index)
            val direct = BrowsePlanner.plan(sources, key)
            assertEquals(direct.map { (it as? SourcePlan.Fetch)?.sections }, indexed.map { (it as? SourcePlan.Fetch)?.sections })
        }
        // A discovered genre matches by words, so "Superhero & Villains" counts too.
        assertTrue(BrowsePlanner.plan(sources, FetchKey(setOf("superhero")), index)[1] is SourcePlan.Fetch)
    }

    @Test fun indexKnowsWhichSourcesItWasBuiltFor() {
        val a = FakeSource("A", listOf("Action"))
        val index = GenreIndex.build(listOf(a))
        assertTrue(index.isFor(listOf(FakeSource("A", listOf("Action")))))
        assertFalse(index.isFor(listOf(FakeSource("A", listOf("Action", "Comedy")))))
        assertFalse(index.isFor(listOf(a, FakeSource("B", listOf("Action")))))
    }

    /** V1: filtering on another thread must never see a half-updated genre set. */
    @Test fun concurrentGenreUpdatesNeverHideAMatch() {
        val agg = BrowseAggregator()
        val types = setOf(TvType.Movie)
        // "Stable" is in both selected genres from the start, so match-all must always keep it.
        agg.add("A", 0, types, setOf("action", "comedy"), listOf(movie("A", "Stable")))
        val pool = agg.snapshot()
        val filters = ClientFilters(matchAllGenres = true)
        val selected = setOf("action", "comedy")
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)

        val writer = thread {
            start.await()
            // Grow the set through many resizes with genres from repeated urls.
            repeat(2_000) { i -> agg.add("A", 0, types, setOf("g$i"), listOf(movie("A", "Stable"))) }
        }
        val reader = thread {
            start.await()
            try {
                repeat(20_000) {
                    val result = BrowseFilter.apply(pool, filters, selected)
                    check(result.size == 1) { "match-all lost the title" }
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        start.countDown()
        writer.join(); reader.join()
        failure.get()?.let { throw it }
        assertEquals(2_002, pool.single().genres.size)
    }

    /** V2: a layout pass reports onScrolled(0, 0); that must not count as a scroll. */
    @Test fun layoutPassIsNotAScroll() {
        assertFalse(BrowsePaging.shouldLoadMore(dy = 0, lastVisible = 5, itemCount = 6, spanCount = 3))
        assertTrue(BrowsePaging.shouldLoadMore(dy = 12, lastVisible = 5, itemCount = 6, spanCount = 3))
        assertFalse(BrowsePaging.shouldLoadMore(dy = 12, lastVisible = 5, itemCount = 60, spanCount = 3))
        assertFalse(BrowsePaging.shouldLoadMore(dy = 12, lastVisible = -1, itemCount = 0, spanCount = 3))
    }

    /** T4: renamed enum values in saved prefs fall back to defaults instead of crashing. */
    @Test fun prefsIgnoreUnknownEnumNames() {
        val filters = BrowsePrefs(
            types = listOf("Movie", "RenamedType"),
            sort = "RenamedSort",
            audio = listOf("Dubbed", "RenamedAudio"),
            minRating = 7.0,
        ).toFilters()
        assertEquals(setOf(TvType.Movie), filters.types)
        assertEquals(BrowseSort.Relevance, filters.sort)
        assertEquals(setOf(DubStatus.Dubbed), filters.audio)
        assertEquals(7.0, filters.minRating!!, 0.0)
        assertEquals(ClientFilters(), BrowsePrefs(sort = null).toFilters())
    }
}
