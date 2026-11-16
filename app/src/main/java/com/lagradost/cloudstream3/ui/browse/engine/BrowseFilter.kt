package com.lagradost.cloudstream3.ui.browse.engine

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus

/** Pure, allocation-light filtering and sorting of loaded titles. O(n) + O(n log n) sort. */
object BrowseFilter {
    fun apply(
        items: List<BrowseItem>,
        filters: ClientFilters,
        selectedGenres: Set<String>,
    ): List<BrowseItem> {
        val queryTokens = GenreMatcher.normalize(filters.query)
            .split(' ').filter { it.isNotEmpty() }
        val filtered = items.filter { item -> matches(item, filters, selectedGenres, queryTokens) }
        return sort(filtered, filters.sort)
    }

    fun matches(
        item: BrowseItem,
        filters: ClientFilters,
        selectedGenres: Set<String>,
        queryTokens: List<String> = GenreMatcher.normalize(filters.query)
            .split(' ').filter { it.isNotEmpty() },
    ): Boolean {
        if (item.excluded) return false
        item.type?.let { if (it in filters.excludedTypes) return false }

        if (filters.types.isNotEmpty()) {
            val type = item.type
            // Unknown type: keep, the provider was already checked to support a selected type.
            if (type != null && type !in filters.types) return false
        }

        if (filters.matchAllGenres && selectedGenres.size > 1 &&
            !item.genres.containsAll(selectedGenres)
        ) return false

        if (filters.yearFrom != null || filters.yearTo != null) {
            val year = item.year
            if (year == null) {
                if (filters.hideUnknown) return false
            } else {
                if (filters.yearFrom != null && year < filters.yearFrom) return false
                if (filters.yearTo != null && year > filters.yearTo) return false
            }
        }

        if (filters.minRating != null) {
            val score = item.score
            if (score == null) {
                if (filters.hideUnknown) return false
            } else if (score < filters.minRating) return false
        }

        if (filters.hideCam && item.response.quality.isCam()) return false

        if (filters.audio.isNotEmpty()) {
            val dub = (item.response as? AnimeSearchResponse)?.dubStatus
            // Non-anime or unknown audio: keep unless hiding unknowns.
            if (dub.isNullOrEmpty() || dub == setOf(DubStatus.None)) {
                if (filters.hideUnknown) return false
            } else if (dub.none { it in filters.audio }) return false
        }

        if (queryTokens.isNotEmpty()) {
            val other = (item.response as? AnimeSearchResponse)?.otherName
                ?.let { GenreMatcher.normalize(it) }.orEmpty()
            val haystack = if (other.isEmpty()) item.normalizedName else item.normalizedName + " " + other
            if (queryTokens.any { !haystack.contains(it) }) return false
        }
        return true
    }

    private val relevance = compareBy<BrowseItem>({ it.position }, { it.providerOrder })

    fun sort(items: List<BrowseItem>, sort: BrowseSort): List<BrowseItem> = when (sort) {
        BrowseSort.Relevance -> items.sortedWith(relevance)
        // Unknown values last, ties keep relevance order.
        BrowseSort.Newest -> items.sortedWith(
            compareBy<BrowseItem> { it.year == null }
                .thenByDescending { it.year ?: 0 }
                .then(relevance)
        )

        BrowseSort.TopRated -> items.sortedWith(
            compareBy<BrowseItem> { it.score == null }
                .thenByDescending { it.score ?: 0.0 }
                .then(relevance)
        )

        BrowseSort.NameAsc -> items.sortedWith(compareBy<BrowseItem> { it.normalizedName }.then(relevance))
    }
}
