package com.lagradost.cloudstream3.ui.browse.engine

object PillLabels {
    /** First name and how many more there are, or null when [names] is empty. */
    fun compact(names: List<String>): Pair<String, Int>? =
        names.firstOrNull()?.let { it to names.size - 1 }
}
