package com.lagradost.cloudstream3.ui.browse

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentBrowseBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.browse.BrowseFilterSheet.Companion.label
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePaging
import com.lagradost.cloudstream3.ui.browse.engine.PillLabels
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSource
import com.lagradost.cloudstream3.ui.browse.engine.GenreIndex
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSort
import com.lagradost.cloudstream3.ui.browse.engine.GenreMatcher
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * Browse titles by genre across installed sources.
 *
 * Arguments: [ARG_GENRE] (a genre label or key, e.g. a tag from the detail page) and optionally
 * [ARG_API] to browse a single source.
 */
class BrowseFragment : BaseFragment<FragmentBrowseBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentBrowseBinding::inflate)
) {
    companion object {
        const val ARG_GENRE = "genre"
        const val ARG_API = "apiName"

        fun args(genre: String?, apiName: String? = null): Bundle = Bundle().apply {
            putString(ARG_GENRE, genre)
            putString(ARG_API, apiName)
        }

        /** Opens Browse for [genre]; with [apiName], only on that source. */
        fun Activity?.openBrowse(genre: String?, apiName: String? = null) =
            navigate(R.id.navigation_browse, args(genre, apiName))
    }

    private val viewModel: BrowseViewModel by viewModels()

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
        binding?.browseResults?.spanCount = view.context.getSpanCount()
        // Equal-width pills: on wide screens keep the row phone-sized instead of stretching.
        binding?.browsePills?.let { row ->
            val maxWidth = 560.toPx
            row.layoutParams = row.layoutParams.apply {
                width = if (view.resources.displayMetrics.widthPixels > maxWidth) maxWidth
                else ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun initViewModel() {
        if (viewModel.isInitialized) return
        val ctx = context ?: return
        // The sources live in the view model across rotations: never capture the Activity.
        val appContext = ctx.applicationContext
        val apiName = arguments?.getString(ARG_API)
        // "Browse X on this source only" from a detail page, even if the language / media
        // settings hide that source (e.g. a title opened from Library or history).
        val single = apiName?.let { name -> apis.firstOrNull { it.name == name } }
        val allowed = if (single != null) listOf(single)
        else ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
        val sources = allowed.map { api ->
            ApiBrowseSource(api) { list -> appContext.filterSearchResultByFilmQuality(list) }
        }
        val genres = arguments?.getString(ARG_GENRE)?.let { GenreMatcher.resolve(it) }.orEmpty().toSet()
        val prefs = BrowsePrefs.load()
        val chosen = if (single != null) setOf(single.name) else prefs.sources.toSet()
        val indexOf: (List<BrowseSource>) -> GenreIndex =
            if (single != null) { list -> GenreIndex.build(list) }
            else { list -> BrowseGenreIndexCache.get(list) }
        viewModel.init(sources, genres, prefs.toFilters(), chosen, indexOf)
    }

    private var lastSavedPrefs: BrowsePrefs? = null

    private fun savePrefs(state: BrowseUiState) {
        // Single-source browsing from a detail page is not remembered.
        if (arguments?.getString(ARG_API) != null) return
        val prefs = BrowsePrefs.from(state.filters, state.chosenSources)
        if (prefs == lastSavedPrefs) return
        if (lastSavedPrefs != null) BrowsePrefs.save(prefs)
        lastSavedPrefs = prefs
    }

    override fun onBindingCreated(binding: FragmentBrowseBinding) {
        initViewModel()
        val adapter = SearchAdapter(binding.browseResults, loadPoster = BrowsePosters::load) { callback ->
            SearchHelper.handleSearchClickCallback(callback)
        }
        binding.apply {
            browseResults.setRecycledViewPool(SearchAdapter.sharedPool)
            browseResults.adapter = adapter
            browseResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as? GridLayoutManager ?: return
                    if (BrowsePaging.shouldLoadMore(
                            dy, manager.findLastVisibleItemPosition(), adapter.itemCount, manager.spanCount
                        )
                    ) viewModel.onEvent(BrowseEvent.LoadMore)
                }
            })

            browseBack.setOnClickListener { dispatchBackPressed() }
            browseFilter.setOnClickListener { openFilterSheet() }
            listOf(browseGenrePill, browseTypePill, browseSortPill).forEach {
                // ChipFilled hides chips from TV focus; pills must be reachable with a remote.
                it.tag = null
                it.setOnCloseIconClickListener { _ -> it.performClick() }
            }
            // Pills are checkable only to show the accent when set; a tap must not toggle them.
            fun restorePills() = viewModel.state.value?.let { render(it, adapter) }
            // Genres are picked in the Filters sheet (with include/exclude and groups); the pill
            // only shows the current choice compactly and opens it.
            browseGenrePill.setOnClickListener { restorePills(); openFilterSheet() }
            browseTypePill.setOnClickListener { restorePills(); pickType() }
            browseSortPill.setOnClickListener { restorePills(); pickSort() }

            browseSearch.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    hideKeyboard()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.onEvent(BrowseEvent.SetQuery(newText.orEmpty().trim()))
                    return true
                }
            })
        }

        observe(viewModel.state) { state -> render(state, adapter) }
    }

    private fun render(state: BrowseUiState, adapter: SearchAdapter) {
        val binding = binding ?: return
        val ctx = binding.root.context
        binding.apply {
            browseGenrePill.text = pillLabel(
                included = state.genres.map { GenreMatcher.displayName(it) },
                excluded = state.excludedGenres.map { GenreMatcher.displayName(it) },
                all = ctx.getString(R.string.browse_any_genre),
            )
            browseGenrePill.isChecked = state.genres.isNotEmpty() || state.excludedGenres.isNotEmpty()

            browseTypePill.text = pillLabel(
                included = BrowseTypeGroup.selected(state.filters.types).map { ctx.getString(it.label) },
                excluded = BrowseTypeGroup.selected(state.filters.excludedTypes).map { ctx.getString(it.label) },
                all = ctx.getString(R.string.browse_all_types),
            )
            browseTypePill.isChecked = state.filters.types.isNotEmpty() || state.filters.excludedTypes.isNotEmpty()

            browseSortPill.text = ctx.getString(state.filters.sort.label())
            browseSortPill.isChecked = state.filters.sort != BrowseSort.Relevance

            browseFilterDot.isVisible = state.filters.hasSheetOnlyFilters || state.chosenSources.isNotEmpty()

            val progress = state.progress
            browseProgress.isVisible = progress != null
            if (progress != null) browseProgress.setProgressCompat((progress * 1000).toInt().coerceAtLeast(30), true)

            adapter.submitList(state.items)

            val idle = progress == null
            val empty = state.items.isEmpty() && idle
            browseEmpty.isVisible = empty
            if (empty) {
                val query = state.filters.query
                browseEmptyText.text = when {
                    state.availableGenres.isEmpty() && state.genres.isEmpty() -> ctx.getString(R.string.browse_no_genres)
                    state.noMatchingSource && state.genres.isEmpty() -> ctx.getString(R.string.browse_no_home_sources)
                    state.noMatchingSource -> ctx.getString(R.string.browse_no_source_for_genre, state.genreLabel)
                    else -> ctx.getString(R.string.browse_no_results)
                }
                val searchFor = query.ifBlank {
                    if (state.noMatchingSource && state.genres.size == 1) state.genreLabel else ""
                }
                if (state.canLoadMore && !state.noMatchingSource) {
                    // Strict filters: nothing matched yet, but more pages exist.
                    browseEmptySearch.isVisible = true
                    browseEmptySearch.text = ctx.getString(R.string.browse_load_more)
                    browseEmptySearch.setOnClickListener { viewModel.onEvent(BrowseEvent.LoadMore) }
                } else {
                    browseEmptySearch.isVisible = searchFor.isNotBlank()
                    browseEmptySearch.text = ctx.getString(R.string.browse_search_all_for, searchFor)
                    browseEmptySearch.setOnClickListener {
                        activity?.navigate(
                            R.id.navigation_search,
                            Bundle().apply { putString("search_query", searchFor) })
                    }
                }
            }

            // A grid that doesn't fill the screen can't be scrolled to load more.
            if (idle && state.canLoadMore && state.items.isNotEmpty()) {
                browseResults.post {
                    if (!browseResults.canScrollVertically(1)) viewModel.onEvent(BrowseEvent.FillScreen)
                }
            }
        }
        savePrefs(state)
    }

    /**
     * Short pill text that never grows with the selection: "Any genre", "Thriller",
     * "Thriller +3", "Not Horror", "Not Horror +1".
     */
    private fun pillLabel(included: List<String>, excluded: List<String>, all: String): String {
        PillLabels.compact(included)?.let { (first, more) ->
            return if (more == 0) first else getString(R.string.browse_pill_more, first, more)
        }
        PillLabels.compact(excluded)?.let { (first, more) ->
            val not = getString(R.string.browse_pill_not, first)
            return if (more == 0) not else getString(R.string.browse_pill_more, not, more)
        }
        return all
    }

    private fun pickType() {
        val state = viewModel.state.value ?: return
        val groups = BrowseTypeGroup.available(viewModel.sourceList)
        val labels = listOf(getString(R.string.browse_all_types)) + groups.map { getString(it.label) }
        val selected = BrowseTypeGroup.selected(state.filters.types)
        val current = if (selected.size == 1) groups.indexOf(selected.first()) + 1 else 0
        activity?.showBottomDialog(labels, current, getString(R.string.browse_type), false, {}) { index ->
            val types = if (index == 0) emptySet() else groups[index - 1].types
            viewModel.onEvent(BrowseEvent.SetTypes(types))
        }
    }

    private fun pickSort() {
        val state = viewModel.state.value ?: return
        val sorts = BrowseSort.entries
        activity?.showBottomDialog(
            sorts.map { getString(it.label()) }, sorts.indexOf(state.filters.sort),
            getString(R.string.browse_sort), false, {}
        ) { index -> viewModel.onEvent(BrowseEvent.SetSort(sorts[index])) }
    }

    private fun openFilterSheet() {
        val activity = activity ?: return
        BrowseFilterSheet(activity, viewModel) { genres, excludedGenres, filters, sources ->
            viewModel.onEvent(BrowseEvent.Apply(genres, filters, sources, excludedGenres))
        }.show()
    }
}
