package com.lagradost.cloudstream3.ui.browse

import android.app.Activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.browse.engine.GenreMatcher
import com.lagradost.cloudstream3.ui.browse.engine.SourceStatus
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper.getFlagFromIso

/**
 * Source selection, reusing the app's multi-select list dialog. Each row shows whether the source
 * can contribute to the selected genre(s). Selecting every source means "all installed", which
 * also includes sources installed later.
 */
object BrowseSourcesPicker {
    fun show(
        activity: Activity,
        viewModel: BrowseViewModel,
        genres: Set<String>,
        chosen: Set<String>,
        onPicked: (Set<String>) -> Unit,
    ) {
        val sources = viewModel.sourceList.sortedBy { it.name.lowercase() }
        if (sources.isEmpty()) return
        val statuses = viewModel.state.value?.statuses.orEmpty().associateBy { it.name }
        val genreLabel = genres.joinToString(", ") { GenreMatcher.displayName(it) }

        val labels = sources.map { source ->
            val sections = if (source.hasMainPage) source.sectionNames.count { name ->
                genres.any { GenreMatcher.matches(name, it) }
            } else 0
            val status = when {
                !source.hasMainPage -> activity.getString(R.string.browse_status_no_home)
                statuses[source.name] is SourceStatus.Failed -> activity.getString(R.string.browse_status_failed)
                statuses[source.name] is SourceStatus.Loading -> activity.getString(R.string.browse_status_loading)
                sections == 0 -> activity.getString(R.string.browse_status_no_section, genreLabel)
                else -> activity.getString(R.string.browse_status_sections, sections, genreLabel)
            }
            val flag = getFlagFromIso(source.lang)?.plus(" ") ?: ""
            "$flag${source.name} · $status"
        }
        val selected = sources.indices.filter { chosen.isEmpty() || sources[it].name in chosen }

        activity.showMultiDialog(labels, selected, activity.getString(R.string.browse_sources), {}) { picked ->
            val names = picked.map { sources[it].name }.toSet()
            onPicked(if (names.size == sources.size || names.isEmpty()) emptySet() else names)
        }
    }
}
