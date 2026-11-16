package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowseAggregator
import com.lagradost.cloudstream3.ui.browse.engine.BrowseFetcher
import com.lagradost.cloudstream3.ui.browse.engine.BrowseFilter
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePageCache
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePlanner
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSession
import com.lagradost.cloudstream3.ui.browse.engine.ChipState
import com.lagradost.cloudstream3.ui.browse.engine.ClientFilters
import com.lagradost.cloudstream3.ui.browse.engine.FetchKey
import com.lagradost.cloudstream3.ui.browse.engine.GenreIndex
import com.lagradost.cloudstream3.ui.browse.engine.GenreMatcher
import com.lagradost.cloudstream3.ui.browse.engine.GenreMatcher.Category
import com.lagradost.cloudstream3.ui.browse.engine.PillLabels
import com.lagradost.cloudstream3.ui.browse.engine.PosterFit
import com.lagradost.cloudstream3.ui.browse.engine.SkipReason
import com.lagradost.cloudstream3.ui.browse.engine.SourcePlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseFollowupsTest {
    // ---- Discovered genre cleanup, regions and platforms ----

    @Test fun junkSectionNamesAreNotGenres() {
        fun key(name: String) = GenreMatcher.discoveredKeyOf(GenreMatcher.tokens(name))
        for (junk in listOf("On Air", "Web Series", "Personal", "Dual Audio", "Hindi Dubbed", "1080p Movies", "ORG Prints")) {
            assertNull(junk, key(junk))
        }
        assertEquals("superhero", key("Superhero Movies"))
    }

    @Test fun platformsAndRegionsAreKnownAndGrouped() {
        assertEquals(setOf("prime video"), GenreMatcher.canonicalGenresOf("Amazon"))
        assertEquals(setOf("prime video"), GenreMatcher.canonicalGenresOf("Amazon Prime Originals"))
        assertEquals(setOf("prime video"), GenreMatcher.canonicalGenresOf("Prime Video"))
        assertEquals(setOf("netflix"), GenreMatcher.canonicalGenresOf("Netflix"))
        assertTrue("hotstar" in GenreMatcher.canonicalGenresOf("Disney Plus Hotstar"))
        assertEquals(setOf("south indian"), GenreMatcher.canonicalGenresOf("South Indian Movies"))
        assertEquals(setOf("indian"), GenreMatcher.canonicalGenresOf("Indian"))
        assertEquals(Category.Platform, GenreMatcher.categoryOf("netflix"))
        assertEquals(Category.Region, GenreMatcher.categoryOf("bollywood"))
        assertEquals(Category.Region, GenreMatcher.categoryOf("korean"))
        assertEquals(Category.Genre, GenreMatcher.categoryOf("thriller"))
        assertEquals(Category.Genre, GenreMatcher.categoryOf("superhero")) // discovered
    }

    @Test fun indexListsNoJunkAndMergesPlatforms() {
        val a = FakeSource("A", listOf("Amazon", "On Air", "Web Series", "Personal", "Dual Audio", "Thriller"))
        val b = FakeSource("B", listOf("Amazon Prime", "Air", "Web", "Personal Picks", "Dual Audio Movies", "Netflix"))
        val keys = GenreIndex.build(listOf(a, b)).available.toMap()
        assertEquals(2, keys["prime video"])
        assertEquals(1, keys["netflix"])
        assertEquals(1, keys["thriller"])
        for (junk in listOf("air", "web", "personal", "dual audio", "amazon", "amazon prime", "picks")) {
            assertFalse(junk, junk in keys)
        }
    }

    // ---- Any genre (the default) ----

    @Test fun anyGenrePlansEveryHomeSection() {
        val a = FakeSource("A", listOf("Trending", "Latest", "Horror"))
        val none = FakeSource("N", listOf("X"), hasMainPage = false)
        val plans = BrowsePlanner.plan(listOf(a, none), FetchKey(emptySet()))
        assertEquals(setOf(0, 1, 2), (plans[0] as SourcePlan.Fetch).sections.keys)
        assertEquals(SkipReason.NoHomePage, (plans[1] as SourcePlan.Skipped).reason)
    }

    @Test fun eachStepLoadsAtMostThreeSectionsPerSourceNewSectionsFirst() {
        val pages = (0 until 5).associateWith { i -> listOf(listOf(movie("A", "p1-$i")), listOf(movie("A", "p2-$i"))) }
        val a = FakeSource("A", (0 until 5).map { "Section $it" }, pages)
        val session = BrowseSession(listOf(a), FetchKey(emptySet()))

        val first = session.firstRequests()
        assertEquals(listOf(0, 1, 2), first.map { it.sectionIndex })
        runTest { BrowseFetcher(BrowsePageCache()).fetch(first).collect { session.onEvent(it) } }

        // Unstarted sections before deeper pages.
        val second = session.nextRequests()
        assertEquals(listOf(3 to 1, 4 to 1, 0 to 2), second.map { it.sectionIndex to it.page })
    }

    // ---- Exclusions ----

    @Test fun excludedGenresDropSectionsAndLoadTheirFirstPageForBlocking() {
        val a = FakeSource("A", listOf("Action", "Horror", "Action & Horror", "Comedy"))
        val plan = BrowsePlanner.plan(listOf(a), FetchKey(emptySet(), excludedGenres = setOf("horror"))).single() as SourcePlan.Fetch
        assertEquals(setOf(0, 3), plan.sections.keys)
        assertEquals(setOf(1, 2), plan.excludeSections)

        val onlyHorror = FakeSource("H", listOf("Horror"))
        val skipped = BrowsePlanner.plan(listOf(onlyHorror), FetchKey(emptySet(), excludedGenres = setOf("horror"))).single()
        assertEquals(SkipReason.NoMatchingSection, (skipped as SourcePlan.Skipped).reason)
    }

    @Test fun excludedTypesSkipProvidersWithOnlyThoseTypes() {
        val anime = FakeSource("Anime", listOf("Action"), supportedTypes = setOf(TvType.Anime))
        val mixed = FakeSource("Mixed", listOf("Action"), supportedTypes = setOf(TvType.Anime, TvType.Movie))
        val plans = BrowsePlanner.plan(listOf(anime, mixed), FetchKey(setOf("action"), excludedTypes = setOf(TvType.Anime)))
        assertEquals(SkipReason.TypeNotSupported, (plans[0] as SourcePlan.Skipped).reason)
        assertTrue(plans[1] is SourcePlan.Fetch)

        val agg = BrowseAggregator()
        agg.add("Mixed", 0, mixed.supportedTypes, emptySet(), listOf(movie("Mixed", "Film"), anime("Mixed", "Show")))
        val shown = BrowseFilter.apply(agg.snapshot(), ClientFilters(excludedTypes = setOf(TvType.Anime)), emptySet())
        assertEquals(listOf("Film"), shown.map { it.response.name })
    }

    @Test fun blockedTitlesAreHiddenBeforeAndAfterTheyArrive() {
        val agg = BrowseAggregator()
        val types = setOf(TvType.Movie)
        agg.add("A", 0, types, emptySet(), listOf(movie("A", "Scream", 1996), movie("A", "Heat", 1995)))
        // Excluded section of provider B lists Scream (cross-provider title match) and Saw.
        agg.block("B", types, listOf(movie("B", "Scream", 1996, url = "/b1"), movie("B", "Saw", 2004, url = "/b2")))
        agg.add("C", 1, types, emptySet(), listOf(movie("C", "Saw", 2004, url = "/c1"), movie("C", "Ronin", 1998, url = "/c2")))
        val shown = BrowseFilter.apply(agg.snapshot(), ClientFilters(), emptySet()).map { it.response.name }
        assertEquals(listOf("Heat", "Ronin"), shown.sorted())
    }

    @Test fun sessionRequestsExclusionPagesFirstAndBlocksTheirTitles() = runTest {
        val a = FakeSource(
            "A", listOf("Trending", "Horror"),
            mapOf(
                0 to listOf(listOf(movie("A", "Scream", 1996), movie("A", "Heat", 1995))),
                1 to listOf(listOf(movie("A", "Scream", 1996, url = "/horror-scream"))),
            ),
        )
        val session = BrowseSession(listOf(a), FetchKey(emptySet(), excludedGenres = setOf("horror")))
        val requests = session.firstRequests()
        assertEquals(listOf(true, false), requests.map { it.exclusion })
        BrowseFetcher(BrowsePageCache()).fetch(requests).collect { session.onEvent(it) }
        val shown = BrowseFilter.apply(session.aggregator.snapshot(), ClientFilters(), emptySet())
        assertEquals(listOf("Heat"), shown.map { it.response.name })
        assertFalse(session.isLoading)
    }

    @Test fun chipStatesCycleAndApply() {
        assertEquals(ChipState.Include, ChipState.Off.next())
        assertEquals(ChipState.Exclude, ChipState.Include.next())
        assertEquals(ChipState.Off, ChipState.Exclude.next())
        assertEquals(ChipState.Exclude, ChipState.of("horror", setOf("action"), setOf("horror")))
        assertEquals(setOf("action") to setOf("horror"), ChipState.apply("horror", ChipState.Exclude, setOf("action", "horror"), emptySet()))
        assertEquals(setOf("action") to emptySet<String>(), ChipState.apply("horror", ChipState.Off, setOf("action"), setOf("horror")))
    }

    // ---- Posters and pills ----

    @Test fun wideImagesAreFittedPortraitOnesCropped() {
        assertFalse(PosterFit.isWide(300, 450))  // 2:3 poster
        assertFalse(PosterFit.isWide(500, 600))  // nearly portrait
        assertTrue(PosterFit.isWide(1280, 720))  // backdrop
        assertTrue(PosterFit.isWide(600, 600))   // square cover
        assertFalse(PosterFit.isWide(0, 0))
    }

    @Test fun initialsForPlaceholders() {
        assertEquals("NM", PosterFit.initials("The Night Manager"))
        assertEquals("SU", PosterFit.initials("Suits"))
        assertEquals("19", PosterFit.initials("1917"))
        assertEquals("AD", PosterFit.initials("Avengers: Doomsday"))
        assertEquals("?", PosterFit.initials("  "))
    }

    @Test fun pillLabelsStayShort() {
        assertNull(PillLabels.compact(emptyList()))
        assertEquals("Thriller" to 0, PillLabels.compact(listOf("Thriller")))
        assertEquals("Thriller" to 3, PillLabels.compact(listOf("Thriller", "Mystery", "Indian", "Crime")))
    }

    @Test fun prefsKeepExcludedTypes() {
        val filters = ClientFilters(excludedTypes = setOf(TvType.Anime, TvType.OVA))
        assertEquals(filters.excludedTypes, BrowsePrefs.from(filters, emptySet()).toFilters().excludedTypes)
        assertEquals(setOf(TvType.Anime), BrowsePrefs(excludedTypes = listOf("Anime", "Gone")).toFilters().excludedTypes)
    }
}
