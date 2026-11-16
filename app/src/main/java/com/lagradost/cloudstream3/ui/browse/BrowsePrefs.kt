package com.lagradost.cloudstream3.ui.browse

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSort
import com.lagradost.cloudstream3.ui.browse.engine.ClientFilters
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val BROWSE_PREFS_KEY = "browse_prefs"

/** Last used Browse filters and sources, per account. The genre is never stored. */
@Serializable
data class BrowsePrefs(
    @JsonProperty("types") @SerialName("types") val types: List<String> = emptyList(),
    @JsonProperty("excludedTypes") @SerialName("excludedTypes") val excludedTypes: List<String> = emptyList(),
    @JsonProperty("sort") @SerialName("sort") val sort: String? = null,
    @JsonProperty("minRating") @SerialName("minRating") val minRating: Double? = null,
    @JsonProperty("yearFrom") @SerialName("yearFrom") val yearFrom: Int? = null,
    @JsonProperty("yearTo") @SerialName("yearTo") val yearTo: Int? = null,
    @JsonProperty("hideUnknown") @SerialName("hideUnknown") val hideUnknown: Boolean = false,
    @JsonProperty("hideCam") @SerialName("hideCam") val hideCam: Boolean = false,
    @JsonProperty("audio") @SerialName("audio") val audio: List<String> = emptyList(),
    @JsonProperty("matchAll") @SerialName("matchAll") val matchAll: Boolean = false,
    @JsonProperty("sources") @SerialName("sources") val sources: List<String> = emptyList(),
) {
    fun toFilters() = ClientFilters(
        types = types.mapNotNullTo(HashSet()) { name -> TvType.entries.firstOrNull { it.name == name } },
        excludedTypes = excludedTypes.mapNotNullTo(HashSet()) { name -> TvType.entries.firstOrNull { it.name == name } },
        sort = BrowseSort.entries.firstOrNull { it.name == sort } ?: BrowseSort.Relevance,
        minRating = minRating,
        yearFrom = yearFrom,
        yearTo = yearTo,
        hideUnknown = hideUnknown,
        hideCam = hideCam,
        audio = audio.mapNotNullTo(HashSet()) { name -> DubStatus.entries.firstOrNull { it.name == name } },
        matchAllGenres = matchAll,
    )

    companion object {
        fun from(filters: ClientFilters, sources: Set<String>) = BrowsePrefs(
            types = filters.types.map { it.name }.sorted(),
            excludedTypes = filters.excludedTypes.map { it.name }.sorted(),
            sort = filters.sort.name,
            minRating = filters.minRating,
            yearFrom = filters.yearFrom,
            yearTo = filters.yearTo,
            hideUnknown = filters.hideUnknown,
            hideCam = filters.hideCam,
            audio = filters.audio.map { it.name }.sorted(),
            matchAll = filters.matchAllGenres,
            sources = sources.sorted(),
        )

        fun load(): BrowsePrefs =
            getKey<BrowsePrefs>("$currentAccount/$BROWSE_PREFS_KEY") ?: BrowsePrefs()

        fun save(prefs: BrowsePrefs) = setKey("$currentAccount/$BROWSE_PREFS_KEY", prefs)
    }
}
