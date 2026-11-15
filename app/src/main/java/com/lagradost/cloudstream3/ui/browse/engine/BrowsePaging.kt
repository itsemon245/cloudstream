package com.lagradost.cloudstream3.ui.browse.engine

object BrowsePaging {
    /**
     * Whether a scroll event should load the next page: only a real downward scroll ([dy] > 0)
     * near the end. RecyclerView also reports `onScrolled(0, 0)` after every layout; treating that
     * as a scroll would page forever on a grid that doesn't fill the screen.
     */
    fun shouldLoadMore(dy: Int, lastVisible: Int, itemCount: Int, spanCount: Int): Boolean =
        dy > 0 && itemCount > 0 && lastVisible >= itemCount - spanCount * 3
}
