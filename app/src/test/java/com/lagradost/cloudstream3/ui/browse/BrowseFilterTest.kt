package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowseAggregator
import com.lagradost.cloudstream3.ui.browse.engine.BrowseFilter
import com.lagradost.cloudstream3.ui.browse.engine.BrowseItem
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSort
import com.lagradost.cloudstream3.ui.browse.engine.ClientFilters
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseFilterTest {
    private fun pool(vararg perSource: Pair<String, List<SearchResponse>>): List<BrowseItem> {
        val agg = BrowseAggregator()
        perSource.forEachIndexed { order, (source, list) ->
            agg.add(source, order, setOf(TvType.Movie, TvType.TvSeries, TvType.Anime), setOf("action"), list)
        }
        return agg.snapshot()
    }

    private fun names(items: List<BrowseItem>) = items.map { it.response.name }

    private val items = pool(
        "A" to listOf(
            movie("A", "Alpha", 2020, 7.5),
            movie("A", "Bravo", null, null, SearchQuality.Cam),
            series("A", "Charlie", 2010),
        ),
        "B" to listOf(
            movie("B", "Delta", 2024, 8.4),
            anime("B", "Echo", DubStatus.Dubbed, otherName = "Ekko no Uta"),
        ),
    )

    @Test fun relevanceInterleavesProviders() {
        assertEquals(listOf("Alpha", "Delta", "Bravo", "Echo", "Charlie"),
            names(BrowseFilter.apply(items, ClientFilters(), setOf("action"))))
    }

    @Test fun typeFilter() {
        assertEquals(listOf("Charlie"),
            names(BrowseFilter.apply(items, ClientFilters(types = setOf(TvType.TvSeries)), emptySet())))
    }

    @Test fun yearKeepsUnknownUnlessHidden() {
        val f = ClientFilters(yearFrom = 2015)
        assertEquals(listOf("Alpha", "Delta", "Bravo", "Echo"), names(BrowseFilter.apply(items, f, emptySet())))
        assertEquals(listOf("Alpha", "Delta"),
            names(BrowseFilter.apply(items, f.copy(hideUnknown = true), emptySet())))
    }

    @Test fun ratingFilter() {
        val f = ClientFilters(minRating = 8.0, hideUnknown = true)
        assertEquals(listOf("Delta"), names(BrowseFilter.apply(items, f, emptySet())))
    }

    @Test fun hideCamAndAudio() {
        assertEquals(listOf("Alpha", "Delta", "Echo", "Charlie"),
            names(BrowseFilter.apply(items, ClientFilters(hideCam = true), emptySet())))
        assertEquals(listOf("Echo"),
            names(BrowseFilter.apply(items, ClientFilters(audio = setOf(DubStatus.Dubbed), hideUnknown = true), emptySet())))
        assertEquals(emptyList<String>(),
            names(BrowseFilter.apply(items, ClientFilters(audio = setOf(DubStatus.Subbed), hideUnknown = true), emptySet())))
    }

    @Test fun inPageSearchMatchesNameAndOtherNameIgnoringCaseAndAccents() {
        assertEquals(listOf("Delta"), names(BrowseFilter.apply(items, ClientFilters(query = "DÉL"), emptySet())))
        assertEquals(listOf("Echo"), names(BrowseFilter.apply(items, ClientFilters(query = "ekko uta"), emptySet())))
        assertEquals(emptyList<String>(), names(BrowseFilter.apply(items, ClientFilters(query = "zzz"), emptySet())))
    }

    @Test fun sorts() {
        assertEquals(listOf("Delta", "Alpha", "Charlie", "Bravo", "Echo"),
            names(BrowseFilter.apply(items, ClientFilters(sort = BrowseSort.Newest), emptySet())))
        assertEquals(listOf("Delta", "Alpha", "Bravo", "Echo", "Charlie"),
            names(BrowseFilter.apply(items, ClientFilters(sort = BrowseSort.TopRated), emptySet())))
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo"),
            names(BrowseFilter.apply(items, ClientFilters(sort = BrowseSort.NameAsc), emptySet())))
    }

    @Test fun matchAllGenresNeedsEveryGenre() {
        val agg = BrowseAggregator()
        val both = movie("A", "Both")
        agg.add("A", 0, setOf(TvType.Movie), setOf("action"), listOf(both, movie("A", "OnlyAction")))
        agg.add("A", 0, setOf(TvType.Movie), setOf("comedy"), listOf(both))
        val f = ClientFilters(matchAllGenres = true)
        assertEquals(listOf("Both"), names(BrowseFilter.apply(agg.snapshot(), f, setOf("action", "comedy"))))
        assertEquals(2, BrowseFilter.apply(agg.snapshot(), f.copy(matchAllGenres = false), setOf("action", "comedy")).size)
    }
}
