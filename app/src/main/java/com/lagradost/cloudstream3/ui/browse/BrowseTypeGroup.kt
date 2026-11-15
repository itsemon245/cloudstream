package com.lagradost.cloudstream3.ui.browse

import androidx.annotation.StringRes
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSource

/** Same grouping and order as the type chips on Home and Search. */
enum class BrowseTypeGroup(@StringRes val label: Int, val types: Set<TvType>) {
    Movies(R.string.movies, setOf(TvType.Movie)),
    TvSeries(R.string.tv_series, setOf(TvType.TvSeries)),
    Anime(R.string.anime, setOf(TvType.Anime, TvType.OVA, TvType.AnimeMovie)),
    Asian(R.string.asian_drama, setOf(TvType.AsianDrama)),
    Cartoons(R.string.cartoons, setOf(TvType.Cartoon)),
    Documentaries(R.string.documentaries, setOf(TvType.Documentary)),
    Live(R.string.livestreams, setOf(TvType.Live)),
    Torrents(R.string.torrent, setOf(TvType.Torrent)),
    Nsfw(R.string.nsfw, setOf(TvType.NSFW)),
    Others(R.string.others, setOf(TvType.Others));

    companion object {
        /** Groups at least one of [sources] can return. */
        fun available(sources: List<BrowseSource>): List<BrowseTypeGroup> =
            entries.filter { group -> sources.any { source -> source.supportedTypes.any { it in group.types } } }

        /** Groups fully contained in [types]. */
        fun selected(types: Set<TvType>): List<BrowseTypeGroup> =
            entries.filter { group -> types.containsAll(group.types) }

        fun typesOf(groups: Collection<BrowseTypeGroup>): Set<TvType> = groups.flatMapTo(HashSet()) { it.types }
    }
}
