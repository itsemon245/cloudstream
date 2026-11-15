package com.lagradost.cloudstream3.ui.browse.engine

import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

/**
 * Collects titles from many providers/sections into one pool.
 *
 * * Same provider + url: merged (genres of every section are unioned).
 * * Same normalised name + year (or both years unknown and same type) on another provider:
 *   merged into the first one, the other provider is recorded in [BrowseItem.alsoOn].
 * * The pool is capped at [maxItems]; later titles are dropped.
 *
 * Not thread-safe: called from a single coroutine.
 */
class BrowseAggregator(private val maxItems: Int = 3000) {
    private val items = ArrayList<BrowseItem>()
    private val byUrl = HashMap<Pair<String, String>, BrowseItem>()
    private val byTitle = HashMap<String, BrowseItem>()
    private val nextPosition = HashMap<String, Int>()

    val size: Int get() = items.size
    val isFull: Boolean get() = items.size >= maxItems

    fun snapshot(): List<BrowseItem> = ArrayList(items)

    fun clear() {
        items.clear(); byUrl.clear(); byTitle.clear(); nextPosition.clear()
    }

    /** @return number of new titles added. */
    fun add(
        sourceName: String,
        providerOrder: Int,
        supportedTypes: Set<TvType>,
        sectionGenres: Set<String>,
        responses: List<SearchResponse>,
    ): Int {
        var added = 0
        val fallbackType = supportedTypes.singleOrNull()
        for (response in responses) {
            val urlKey = sourceName to response.url
            byUrl[urlKey]?.let { existing ->
                if (!existing.genres.containsAll(sectionGenres)) existing.genres = existing.genres + sectionGenres
                continue
            }
            val type = response.type ?: fallbackType
            val year = response.yearOrNull()
            val titleKey = GenreMatcher.normalize(response.name) + "|" +
                    (year?.toString() ?: "?" + (type?.name ?: ""))
            val sameTitle = byTitle[titleKey]
            if (sameTitle != null && sameTitle.response.apiName != sourceName) {
                if (!sameTitle.genres.containsAll(sectionGenres)) sameTitle.genres = sameTitle.genres + sectionGenres
                sameTitle.alsoOn = sameTitle.alsoOn + sourceName
                byUrl[urlKey] = sameTitle
                continue
            }
            if (isFull) continue
            val position = nextPosition[sourceName] ?: 0
            nextPosition[sourceName] = position + 1
            val item = BrowseItem(
                response = response,
                type = type,
                genres = sectionGenres,
                position = position,
                providerOrder = providerOrder,
            )
            items += item
            byUrl[urlKey] = item
            byTitle.putIfAbsent(titleKey, item)
            added++
        }
        return added
    }
}
