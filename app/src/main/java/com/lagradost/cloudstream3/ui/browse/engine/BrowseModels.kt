package com.lagradost.cloudstream3.ui.browse.engine

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType

enum class BrowseSort { Relevance, Newest, TopRated, NameAsc }

/**
 * Filters applied to titles that are already loaded. Changing any of these never causes a
 * network request.
 */
data class ClientFilters(
    /** Empty = every type. */
    val types: Set<TvType> = emptySet(),
    /** Types to hide. Exact: every title knows its own type. */
    val excludedTypes: Set<TvType> = emptySet(),
    val sort: BrowseSort = BrowseSort.Relevance,
    /** Minimum score out of 10; null = any. */
    val minRating: Double? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** Drop titles whose year/rating is unknown when a year/rating filter is active. */
    val hideUnknown: Boolean = false,
    val hideCam: Boolean = false,
    /** Empty = any. Only anime results carry dub status. */
    val audio: Set<DubStatus> = emptySet(),
    /** Require every selected genre (best-effort) instead of any. */
    val matchAllGenres: Boolean = false,
    /** In-page search over loaded titles. */
    val query: String = "",
) {
    /** True when something beyond the pills (genre, type, sort) is set. */
    val hasSheetOnlyFilters: Boolean
        get() = minRating != null || yearFrom != null || yearTo != null || hideUnknown ||
                hideCam || audio.isNotEmpty() || matchAllGenres || excludedTypes.isNotEmpty()
}

/** What has to be fetched. Changing it starts a new fetch (cached pages are reused). */
data class FetchKey(
    /** Genres to include; empty = any genre (every section). */
    val genres: Set<String>,
    /** Provider names; empty = all installed (after the language setting). */
    val sources: Set<String> = emptySet(),
    /** Used only to skip providers that can't contain the selected types. */
    val types: Set<TvType> = emptySet(),
    /**
     * Genres to leave out. Sections that match them are not browsed; their first page is
     * loaded only to learn which titles to hide (see [BrowseAggregator.block]).
     */
    val excludedGenres: Set<String> = emptySet(),
    /** Used only to skip providers whose every type is excluded. */
    val excludedTypes: Set<TvType> = emptySet(),
)

/**
 * A loaded title, possibly listed by several providers / sections.
 *
 * Items are updated by the aggregator on one thread while [BrowseFilter] reads them on another,
 * so the sets are immutable and replaced as a whole (copy-on-write) — a reader always sees a
 * complete old or new set, never a half-updated one.
 */
class BrowseItem(
    val response: SearchResponse,
    /** Effective type: the response's own type, or the provider's only type. */
    val type: TvType?,
    genres: Set<String>,
    /** Index of the title within its provider's stream; used for relevance interleaving. */
    val position: Int,
    /** Order of the provider in the plan; tie-breaker for relevance. */
    val providerOrder: Int,
) {
    /** Genre keys of every section this title was found in. */
    @Volatile
    var genres: Set<String> = genres.toSet()
        internal set

    /** Other providers that list the same title. */
    @Volatile
    var alsoOn: Set<String> = emptySet()
        internal set

    /** Listed in a section of an excluded genre: hidden. */
    @Volatile
    var excluded: Boolean = false
        internal set

    val year: Int? get() = response.yearOrNull()
    val score: Double? get() = response.score?.toDouble(10)
    val normalizedName: String by lazy { GenreMatcher.normalize(response.name) }
}

fun SearchResponse.yearOrNull(): Int? = when (this) {
    is MovieSearchResponse -> year
    is TvSeriesSearchResponse -> year
    is AnimeSearchResponse -> year
    else -> null
}

private val CAM_QUALITIES = setOf(
    SearchQuality.Cam, SearchQuality.CamRip, SearchQuality.HdCam,
    SearchQuality.Telesync, SearchQuality.Telecine, SearchQuality.WorkPrint,
)

fun SearchQuality?.isCam(): Boolean = this != null && this in CAM_QUALITIES

/** Status of one provider for the current fetch. */
sealed class SourceStatus {
    abstract val name: String

    data class Loading(override val name: String, val sections: Int) : SourceStatus()
    data class Loaded(override val name: String, val sections: Int, val titles: Int) : SourceStatus()
    data class Failed(override val name: String, val sections: Int, val message: String?) : SourceStatus()
    data class Skipped(override val name: String, val reason: SkipReason) : SourceStatus()
}

enum class SkipReason { NoHomePage, NoMatchingSection, TypeNotSupported }

/** A filter chip that can include, exclude or ignore its value. Tap cycles Off → Include → Exclude. */
enum class ChipState {
    Off, Include, Exclude;

    fun next(): ChipState = when (this) {
        Off -> Include
        Include -> Exclude
        Exclude -> Off
    }

    companion object {
        fun of(value: String, included: Set<String>, excluded: Set<String>): ChipState = when (value) {
            in included -> Include
            in excluded -> Exclude
            else -> Off
        }

        /** New (included, excluded) after [value] moves to [state]. */
        fun <T> apply(value: T, state: ChipState, included: Set<T>, excluded: Set<T>): Pair<Set<T>, Set<T>> =
            when (state) {
                Off -> (included - value) to (excluded - value)
                Include -> (included + value) to (excluded - value)
                Exclude -> (included - value) to (excluded + value)
            }
    }
}
