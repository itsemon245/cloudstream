package com.lagradost.cloudstream3.ui.browse

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentBrowseBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.browse.BrowseFilterSheet.Companion.label
import com.lagradost.cloudstream3.ui.browse.engine.BrowsePageCache
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

        init {
            // Cached pages belong to the old plugin instances.
            afterPluginsLoadedEvent += { forceReload -> if (forceReload) BrowsePageCache.shared.clear() }
        }
    }

    private val viewModel: BrowseViewModel by viewModels()

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
        binding?.browseResults?.spanCount = view.context.getSpanCount()
    }

    private fun initViewModel() {
        if (viewModel.isInitialized) return
        val ctx = context ?: return
        val apiName = arguments?.getString(ARG_API)
        val allowed = ctx.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
        val sources = allowed.map { api ->
            ApiBrowseSource(api) { list -> ctx.filterSearchResultByFilmQuality(list) }
        }
        val genres = arguments?.getString(ARG_GENRE)?.let { GenreMatcher.resolve(it) }.orEmpty().toSet()
        val prefs = BrowsePrefs.load()
        val chosen = when {
            // "Browse X on this source only" from a detail page.
            apiName != null && apis.any { it.name == apiName } -> setOf(apiName)
            else -> prefs.sources.toSet()
        }
        viewModel.init(sources, genres, prefs.toFilters(), chosen)
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
        val adapter = SearchAdapter(binding.browseResults) { callback ->
            SearchHelper.handleSearchClickCallback(callback)
        }
        binding.apply {
            browseResults.setRecycledViewPool(SearchAdapter.sharedPool)
            browseResults.adapter = adapter
            browseResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val last = manager.findLastVisibleItemPosition()
                    if (last >= adapter.itemCount - manager.spanCount * 3) {
                        viewModel.onEvent(BrowseEvent.LoadMore)
                    }
                }
            })

            browseBack.setOnClickListener { dispatchBackPressed() }
            browseFilter.setOnClickListener { openFilterSheet() }
            listOf(browseGenrePill, browseTypePill, browseSortPill).forEach {
                // ChipFilled hides chips from TV focus; pills must be reachable with a remote.
                it.tag = null
                it.setOnCloseIconClickListener { _ -> it.performClick() }
            }
            browseGenrePill.setOnClickListener { pickGenre() }
            browseTypePill.setOnClickListener { pickType() }
            browseSortPill.setOnClickListener { pickSort() }

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
            browseGenrePill.text = state.genreLabel.ifEmpty { ctx.getString(R.string.browse_genre) }
            browseGenrePill.isChecked = state.genres.isNotEmpty()

            val typeGroups = BrowseTypeGroup.selected(state.filters.types)
            browseTypePill.text = when {
                state.filters.types.isEmpty() -> ctx.getString(R.string.browse_all_types)
                typeGroups.size == 1 -> ctx.getString(typeGroups.first().label)
                else -> typeGroups.joinToString(", ") { ctx.getString(it.label) }
            }
            browseTypePill.isChecked = state.filters.types.isNotEmpty()

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
                    state.noMatchingSource -> ctx.getString(R.string.browse_no_source_for_genre, state.genreLabel)
                    else -> ctx.getString(R.string.browse_no_results)
                }
                val searchFor = query.ifBlank { if (state.noMatchingSource) state.genreLabel else "" }
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

    private fun pickGenre() {
        val state = viewModel.state.value ?: return
        val options = state.availableGenres
        if (options.isEmpty()) return
        val current = options.indexOfFirst { it.key in state.genres }
        activity?.showBottomDialog(
            options.map { "${it.name} · ${it.sources}" }, current,
            getString(R.string.browse_genre), false, {}
        ) { index -> viewModel.onEvent(BrowseEvent.SetGenres(setOf(options[index].key))) }
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
        BrowseFilterSheet(activity, viewModel) { genres, filters, sources ->
            viewModel.onEvent(BrowseEvent.Apply(genres, filters, sources))
        }.show()
    }
}
