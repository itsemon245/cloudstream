package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePlanner
import com.lagradost.cloudstream3.ui.browse.engine.FetchKey
import com.lagradost.cloudstream3.ui.browse.engine.SkipReason
import com.lagradost.cloudstream3.ui.browse.engine.SourcePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsePlannerTest {
    private val a = FakeSource("A", listOf("Trending", "Action Movies", "Horror", "Action & Adventure"))
    private val b = FakeSource("B", listOf("Latest", "Popular"))
    private val c = FakeSource("C", listOf("Action"), hasMainPage = false)
    private val d = FakeSource("D", listOf("Action"), supportedTypes = setOf(TvType.Anime))

    @Test fun picksOnlyMatchingSections() {
        val plans = BrowsePlanner.plan(listOf(a, b, c, d), FetchKey(setOf("action")))
        val fetchA = plans[0] as SourcePlan.Fetch
        assertEquals(setOf(1, 3), fetchA.sections.keys)
        assertEquals(SkipReason.NoMatchingSection, (plans[1] as SourcePlan.Skipped).reason)
        assertEquals(SkipReason.NoHomePage, (plans[2] as SourcePlan.Skipped).reason)
        assertTrue(plans[3] is SourcePlan.Fetch)
    }

    @Test fun skipsProvidersWithoutSelectedTypes() {
        val plans = BrowsePlanner.plan(listOf(a, d), FetchKey(setOf("action"), types = setOf(TvType.Movie)))
        assertTrue(plans[0] is SourcePlan.Fetch)
        assertEquals(SkipReason.TypeNotSupported, (plans[1] as SourcePlan.Skipped).reason)
    }

    @Test fun respectsChosenSourcesAndMultipleGenres() {
        val plans = BrowsePlanner.plan(listOf(a, b, d), FetchKey(setOf("action", "horror"), sources = setOf("A")))
        assertEquals(1, plans.size)
        val fetch = plans.single() as SourcePlan.Fetch
        assertEquals(setOf(1, 2, 3), fetch.sections.keys)
        assertEquals(setOf("horror"), fetch.sections[2])
    }

    @Test fun ordersOnlyFetchedProviders() {
        val plans = BrowsePlanner.plan(listOf(b, a, d), FetchKey(setOf("action")))
        assertEquals(0, (plans[1] as SourcePlan.Fetch).order)
        assertEquals(1, (plans[2] as SourcePlan.Fetch).order)
    }

    @Test fun availableGenresCountsProvidersNotSections() {
        val e = FakeSource("E", listOf("Action Hits", "More Action", "Comedy"))
        val genres = BrowsePlanner.availableGenres(listOf(a, e, c)).toMap()
        assertEquals(2, genres["action"])
        assertEquals(1, genres["comedy"])
        assertEquals(1, genres["horror"])
        assertEquals("action", BrowsePlanner.availableGenres(listOf(a, e)).first().first)
    }
}
