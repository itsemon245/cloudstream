package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePageCache
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSource
import com.lagradost.cloudstream3.ui.browse.engine.GenreIndex

/**
 * The [GenreIndex] of the installed providers, built once and reused by Search's genre row, the
 * Browse page, the filter sheet and the source picker. Rebuilt when plugins are (re)loaded or
 * when asked for a different set of providers. Call [get] off the main thread.
 */
object BrowseGenreIndexCache {
    @Volatile
    private var cached: GenreIndex? = null

    init {
        afterPluginsLoadedEvent += { forceReload ->
            cached = null
            // Cached pages belong to the old plugin instances.
            if (forceReload) BrowsePageCache.shared.clear()
        }
    }

    @Synchronized
    fun get(sources: List<BrowseSource>): GenreIndex {
        cached?.takeIf { it.isFor(sources) }?.let { return it }
        return GenreIndex.build(sources).also { cached = it }
    }
}
