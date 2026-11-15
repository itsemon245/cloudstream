package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowseAggregator
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePageCache
import com.lagradost.cloudstream3.ui.browse.engine.SectionPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseAggregatorCacheTest {
    private val types = setOf(TvType.Movie)

    @Test fun mergesSameUrlAndSameTitleAcrossProviders() {
        val agg = BrowseAggregator()
        assertEquals(2, agg.add("A", 0, types, setOf("action"), listOf(movie("A", "Heat", 1995), movie("A", "Ronin", 1998))))
        // Same url again from another section: merged, genre added.
        assertEquals(0, agg.add("A", 0, types, setOf("crime"), listOf(movie("A", "Heat", 1995))))
        // Same title + year on another provider: merged.
        assertEquals(0, agg.add("B", 1, types, setOf("action"), listOf(movie("B", "HEAT", 1995, url = "/x"))))
        // Same title, different year: a different film.
        assertEquals(1, agg.add("B", 1, types, setOf("action"), listOf(movie("B", "Heat", 1986, url = "/y"))))

        val heat = agg.snapshot().first()
        assertEquals(setOf("action", "crime"), heat.genres)
        assertEquals(setOf("B"), heat.alsoOn)
        assertEquals(3, agg.size)
    }

    @Test fun sameTitleOnSameProviderIsNotMerged() {
        val agg = BrowseAggregator()
        agg.add("A", 0, types, emptySet(), listOf(movie("A", "Dune", url = "/1"), movie("A", "Dune", url = "/2")))
        assertEquals(2, agg.size)
    }

    @Test fun fallbackTypeFromSingleTypeProvider() {
        val agg = BrowseAggregator()
        val untyped = movie("A", "X").apply { type = null }
        agg.add("A", 0, setOf(TvType.Anime), emptySet(), listOf(untyped))
        agg.add("B", 1, setOf(TvType.Anime, TvType.Movie), emptySet(), listOf(movie("B", "Y").apply { type = null }))
        assertEquals(listOf(TvType.Anime, null), agg.snapshot().map { it.type })
    }

    @Test fun capsPool() {
        val agg = BrowseAggregator(maxItems = 2)
        agg.add("A", 0, types, emptySet(), (1..5).map { movie("A", "M$it") })
        assertEquals(2, agg.size)
        assertTrue(agg.isFull)
    }

    @Test fun cacheExpiresAndEvictsLeastRecentlyUsed() {
        var now = 0L
        val cache = BrowsePageCache(maxEntries = 2, ttlMs = 100, clock = { now })
        val k1 = BrowsePageCache.Key("A", "Action", 0, 1)
        val k2 = BrowsePageCache.Key("A", "Action", 0, 2)
        val k3 = BrowsePageCache.Key("B", "Action", 0, 1)
        val page = SectionPage(emptyList(), false)
        cache.put(k1, page); cache.put(k2, page)
        assertNotNull(cache.get(k1)) // k1 now most recent
        cache.put(k3, page)          // evicts k2
        assertNull(cache.get(k2))
        assertNotNull(cache.get(k1))
        now = 101
        assertNull(cache.get(k1))
        cache.clear()
        assertEquals(0, cache.size)
    }
}
