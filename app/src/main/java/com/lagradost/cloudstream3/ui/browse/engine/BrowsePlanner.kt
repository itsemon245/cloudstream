package com.lagradost.cloudstream3.ui.browse.engine

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/** A page of one provider section. */
data class SectionPage(val items: List<SearchResponse>, val hasNext: Boolean)

/**
 * What Browse needs from a provider. Implemented over `APIRepository` in the app and by fakes
 * in tests, so the engine never touches Android or the network directly.
 */
interface BrowseSource {
    val name: String
    val lang: String
    val supportedTypes: Set<TvType>
    val hasMainPage: Boolean

    /** `MainAPI.mainPage` names, in order. */
    val sectionNames: List<String>

    /** Pause between two requests to this provider (sequentialMainPage providers). */
    val requestDelayMs: Long

    /** Loads one page of one section. Throws on failure. */
    suspend fun loadSection(sectionIndex: Int, page: Int): SectionPage
}

sealed class SourcePlan {
    abstract val source: BrowseSource

    data class Fetch(
        override val source: BrowseSource,
        /** Order of the provider; used for relevance tie-breaks. */
        val order: Int,
        /** Section index -> genre keys it matched. */
        val sections: Map<Int, Set<String>>,
    ) : SourcePlan()

    data class Skipped(override val source: BrowseSource, val reason: SkipReason) : SourcePlan()
}

/** Decides, without any network access, which sections of which providers to load. */
object BrowsePlanner {
    fun plan(sources: List<BrowseSource>, key: FetchKey): List<SourcePlan> {
        var order = 0
        return sources
            .filter { key.sources.isEmpty() || it.name in key.sources }
            .map { source ->
                when {
                    !source.hasMainPage || source.sectionNames.isEmpty() ->
                        SourcePlan.Skipped(source, SkipReason.NoHomePage)

                    key.types.isNotEmpty() && source.supportedTypes.none { it in key.types } ->
                        SourcePlan.Skipped(source, SkipReason.TypeNotSupported)

                    else -> {
                        val sections = LinkedHashMap<Int, Set<String>>()
                        source.sectionNames.forEachIndexed { index, sectionName ->
                            val matched = key.genres.filterTo(LinkedHashSet()) {
                                GenreMatcher.matches(sectionName, it)
                            }
                            if (matched.isNotEmpty()) sections[index] = matched
                        }
                        if (sections.isEmpty()) SourcePlan.Skipped(source, SkipReason.NoMatchingSection)
                        else SourcePlan.Fetch(source, order++, sections)
                    }
                }
            }
    }

    /**
     * Genre keys with the number of providers that have at least one matching section, most
     * common first. Used for the genre pickers and the Search entry row.
     */
    fun availableGenres(sources: List<BrowseSource>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (source in sources) {
            if (!source.hasMainPage) continue
            val genres = source.sectionNames.flatMapTo(HashSet()) { GenreMatcher.canonicalGenresOf(it) }
            for (genre in genres) counts[genre] = (counts[genre] ?: 0) + 1
        }
        val canonicalOrder = GenreMatcher.CANONICAL.map { it.key }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { canonicalOrder.indexOf(it.key) })
            .map { it.key to it.value }
    }
}
