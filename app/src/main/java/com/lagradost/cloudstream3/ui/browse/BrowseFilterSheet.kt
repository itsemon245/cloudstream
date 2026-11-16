package com.lagradost.cloudstream3.ui.browse

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Paint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.BrowseFilterSheetBinding
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSort
import com.lagradost.cloudstream3.ui.browse.engine.ChipState
import com.lagradost.cloudstream3.ui.browse.engine.ClientFilters
import com.lagradost.cloudstream3.ui.browse.engine.GenreMatcher
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import java.util.Calendar

/**
 * The full filter sheet. A bottom sheet on phones, a right-side panel in landscape and on TV.
 * Works on a local copy of the settings and hands them back on "Show N titles".
 */
class BrowseFilterSheet(
    private val activity: Activity,
    private val viewModel: BrowseViewModel,
    private val onApply: (
        genres: Set<String>,
        excludedGenres: Set<String>,
        filters: ClientFilters,
        sources: Set<String>,
    ) -> Unit,
) {
    private val state = viewModel.state.value ?: BrowseUiState()
    private var genres: Set<String> = state.genres
    private var excludedGenres: Set<String> = state.excludedGenres
    private var filters: ClientFilters = state.filters
    private var sources: Set<String> = state.chosenSources
    private var showAllGenres = false

    private val binding = BrowseFilterSheetBinding.inflate(LayoutInflater.from(activity))
    private val dialog: Dialog = createDialog()

    private fun createDialog(): Dialog {
        val sidePanel = isLandscape() || isLayout(TV or EMULATOR)
        return if (sidePanel) {
            Dialog(activity, R.style.AlertDialogCustom).apply {
                setContentView(binding.root)
                window?.apply {
                    setGravity(Gravity.END)
                    setLayout(400.toPx, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            }
        } else {
            BottomSheetDialog(activity).apply {
                setContentView(binding.root)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
    }

    fun show() {
        bind()
        dialog.show()
    }

    private fun chip(group: ChipGroup, text: String, checked: Boolean, onToggle: (Boolean) -> Unit): Chip {
        val chip = LayoutInflater.from(activity).inflate(R.layout.browse_chip, group, false) as Chip
        chip.text = text
        chip.isChecked = checked
        chip.setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        group.addView(chip)
        return chip
    }

    /** A chip that cycles Off -> Include -> Exclude on tap; excluded chips read "− Name". */
    private fun triChip(group: ChipGroup, text: String, state: ChipState, onChange: (ChipState) -> Unit): Chip {
        val chip = LayoutInflater.from(activity).inflate(R.layout.browse_chip, group, false) as Chip
        chip.isChecked = state == ChipState.Include
        if (state == ChipState.Exclude) {
            chip.text = activity.getString(R.string.browse_excluded_chip, text)
            chip.paintFlags = chip.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            chip.chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.colorOngoing))
            chip.chipStrokeWidth = 1.5f.toPx
            chip.contentDescription = activity.getString(R.string.browse_excluded_desc, text)
        } else {
            chip.text = text
        }
        // The click has already toggled "checked"; the state, not the toggle, decides the look.
        chip.setOnClickListener { onChange(state.next()) }
        group.addView(chip)
        return chip
    }

    private fun bind(): BrowseFilterSheetBinding = binding.apply {
        // Genres: grouped as Genre / Region / Platform. Most common first, a few visible per
        // group, "+N more" expands in place. Each chip is tri-state: include, exclude, off.
        val known = state.availableGenres.map { it.key to it.name } +
                (genres + excludedGenres).filter { key -> state.availableGenres.none { it.key == key } }
                    .map { it to GenreMatcher.displayName(it) }
        val groups = listOf(
            Triple(GenreMatcher.Category.Genre, filterGenres, null),
            Triple(GenreMatcher.Category.Region, filterRegions, filterRegionsLabel),
            Triple(GenreMatcher.Category.Platform, filterPlatforms, filterPlatformsLabel),
        )
        for ((category, group, label) in groups) {
            group.removeAllViews()
            if (category == GenreMatcher.Category.Genre) {
                // "Any genre" clears the included genres (exclusions stay).
                chip(group, activity.getString(R.string.browse_any_genre), genres.isEmpty()) {
                    genres = emptySet(); bind()
                }
            }
            val all = known.filter { GenreMatcher.categoryOf(it.first) == category }
            val visible = if (showAllGenres) all else {
                val top = all.take(VISIBLE_GENRES)
                top + all.filter { (it.first in genres || it.first in excludedGenres) && it !in top }
            }
            for ((key, name) in visible) {
                triChip(group, name, ChipState.of(key, genres, excludedGenres)) { next ->
                    val (inc, exc) = ChipState.apply(key, next, genres, excludedGenres)
                    genres = inc; excludedGenres = exc
                    bind()
                }
            }
            if (!showAllGenres && all.size > visible.size) {
                chip(group, activity.getString(R.string.browse_more_genres, all.size - visible.size), false) {}
                    .apply {
                        isCheckable = false
                        setOnClickListener {
                            showAllGenres = true
                            bind()
                        }
                    }
            }
            val empty = all.isEmpty() && category != GenreMatcher.Category.Genre
            group.isVisible = !empty
            label?.isVisible = !empty
        }

        // Type: tri-state as well; exact, since every title knows its own type.
        filterTypes.removeAllViews()
        for (group in BrowseTypeGroup.available(viewModel.sourceList)) {
            val state = when {
                filters.types.containsAll(group.types) -> ChipState.Include
                filters.excludedTypes.containsAll(group.types) -> ChipState.Exclude
                else -> ChipState.Off
            }
            triChip(filterTypes, activity.getString(group.label), state) { next ->
                val included = filters.types - group.types
                val excluded = filters.excludedTypes - group.types
                filters = when (next) {
                    ChipState.Off -> filters.copy(types = included, excludedTypes = excluded)
                    ChipState.Include -> filters.copy(types = included + group.types, excludedTypes = excluded)
                    ChipState.Exclude -> filters.copy(types = included, excludedTypes = excluded + group.types)
                }
                bind()
            }
        }

        // Sort (single choice)
        filterSort.removeAllViews()
        for (sort in BrowseSort.entries) {
            chip(filterSort, activity.getString(sort.label()), sort == filters.sort) { checked ->
                if (checked) {
                    filters = filters.copy(sort = sort); refresh()
                }
            }
        }

        // Rating (single choice)
        filterRating.removeAllViews()
        for (min in RATING_STEPS) {
            val text = if (min == null) activity.getString(R.string.browse_any)
            else activity.getString(R.string.browse_rating_min, min)
            chip(filterRating, text, filters.minRating == min?.toDouble()) { checked ->
                if (checked) {
                    filters = filters.copy(minRating = min?.toDouble()); refresh()
                }
            }
        }

        // Sources
        filterSourcesRow.setOnClickListener { pickSources() }

        // More
        filterMoreRow.setOnClickListener {
            filterMore.isVisible = !filterMore.isVisible
            filterMoreArrow.rotation = if (filterMore.isVisible) 180f else 0f
        }
        if (filters.hasSheetOnlyFilters && !filterMore.isVisible) {
            filterMore.isVisible = true
            filterMoreArrow.rotation = 180f
        }
        filterYearFrom.setOnClickListener {
            pickYear(filters.yearFrom) { filters = filters.copy(yearFrom = it); refresh() }
        }
        filterYearTo.setOnClickListener {
            pickYear(filters.yearTo) { filters = filters.copy(yearTo = it); refresh() }
        }

        filterAudio.removeAllViews()
        for ((status, label) in listOf(DubStatus.Subbed to R.string.browse_audio_sub, DubStatus.Dubbed to R.string.browse_audio_dub)) {
            chip(filterAudio, activity.getString(label), status in filters.audio) { checked ->
                filters = filters.copy(audio = if (checked) filters.audio + status else filters.audio - status)
                refresh()
            }
        }

        filterHideCam.setOnCheckedChangeListener(null)
        filterHideCam.isChecked = filters.hideCam
        filterHideCam.setOnCheckedChangeListener { _, c -> filters = filters.copy(hideCam = c); refresh() }
        filterMatchAll.setOnCheckedChangeListener(null)
        filterMatchAll.isChecked = filters.matchAllGenres
        filterMatchAll.setOnCheckedChangeListener { _, c -> filters = filters.copy(matchAllGenres = c); refresh() }
        filterHideUnknown.setOnCheckedChangeListener(null)
        filterHideUnknown.isChecked = filters.hideUnknown
        filterHideUnknown.setOnCheckedChangeListener { _, c -> filters = filters.copy(hideUnknown = c); refresh() }

        filterReset.setOnClickListener {
            // Back to the defaults: any genre, all types, all sources.
            filters = ClientFilters(query = filters.query)
            genres = emptySet()
            excludedGenres = emptySet()
            sources = emptySet()
            bind()
        }
        filterApply.setOnClickListener {
            onApply(genres, excludedGenres, filters, sources)
            dialog.dismissSafe(activity)
        }
        refresh()
    }

    /** Updates texts that depend on the current selection. */
    private fun refresh(): BrowseFilterSheetBinding = binding.apply {
        filterMatchAll.isVisible = genres.size > 1
        filterYearFrom.text = filters.yearFrom?.toString() ?: activity.getString(R.string.browse_any)
        filterYearTo.text = filters.yearTo?.toString() ?: activity.getString(R.string.browse_any)

        val index = viewModel.genreIndex
        val selected = viewModel.sourceList.filter { source ->
            (sources.isEmpty() || source.name in sources) && source.hasMainPage
        }
        val which = if (sources.isEmpty()) activity.getString(R.string.browse_all_sources)
        else activity.getString(R.string.browse_chosen_sources, sources.size)
        filterSourcesValue.text = if (genres.isEmpty()) {
            activity.getString(R.string.browse_sources_summary_any, which, selected.size)
        } else {
            val withGenre = selected.count { index.sectionsFor(it, genres).isNotEmpty() }
            val genreLabel = genres.joinToString(", ") { GenreMatcher.displayName(it) }
            activity.getString(R.string.browse_sources_summary, which, withGenre, genreLabel)
        }

        val count = viewModel.previewCount(genres, filters, sources, excludedGenres)
        filterApply.text = if (count == null) activity.getString(R.string.browse_show)
        else activity.getString(R.string.browse_show_titles, count)
    }

    private fun pickYear(current: Int?, onPicked: (Int?) -> Unit) {
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val years = listOf<Int?>(null) + (thisYear + 1 downTo 1920).toList()
        val labels = years.map { it?.toString() ?: activity.getString(R.string.browse_any) }
        activity.showBottomDialog(
            labels, years.indexOf(current).coerceAtLeast(0),
            activity.getString(R.string.browse_year), false, {}
        ) { index -> onPicked(years[index]) }
    }

    private fun pickSources() {
        BrowseSourcesPicker.show(activity, viewModel, genres, sources) { picked ->
            sources = picked
            refresh()
        }
    }

    companion object {
        private const val VISIBLE_GENRES = 7
        private val RATING_STEPS = listOf(null, 6, 7, 8)

        fun BrowseSort.label(): Int = when (this) {
            BrowseSort.Relevance -> R.string.browse_sort_relevance
            BrowseSort.Newest -> R.string.browse_sort_newest
            BrowseSort.TopRated -> R.string.browse_sort_top_rated
            BrowseSort.NameAsc -> R.string.browse_sort_name
        }
    }
}
