package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSource
import com.lagradost.cloudstream3.ui.browse.engine.SectionPage

class BrowseLoadException(message: String) : Exception(message)

/**
 * [BrowseSource] over an installed extension. Requests go through [APIRepository], which applies
 * the provider's timeout and error handling exactly like the Home page does.
 */
class ApiBrowseSource(
    api: MainAPI,
    /** e.g. the user's "hide these qualities" setting. */
    private val postFilter: (List<SearchResponse>) -> List<SearchResponse> = { it },
) : BrowseSource {
    private val repo = APIRepository(api)

    override val name: String = api.name
    override val lang: String = api.lang
    override val supportedTypes: Set<TvType> = api.supportedTypes
    override val hasMainPage: Boolean = api.hasMainPage
    override val sectionNames: List<String> = api.mainPage.map { it.name }
    override val requestDelayMs: Long =
        if (api.sequentialMainPage) maxOf(api.sequentialMainPageDelay, api.sequentialMainPageScrollDelay) else 0L

    override suspend fun loadSection(sectionIndex: Int, page: Int): SectionPage =
        when (val result = repo.getMainPage(page, sectionIndex)) {
            is Resource.Success -> {
                val responses = result.value.filterNotNull()
                SectionPage(
                    items = postFilter(responses.flatMap { response -> response.items.flatMap { it.list } }),
                    hasNext = responses.any { it.hasNext },
                )
            }

            is Resource.Failure -> throw BrowseLoadException(result.errorString)
            is Resource.Loading -> throw BrowseLoadException("Unexpected loading state")
        }
}

/**
 * Genres the given providers have home-page sections for, most common first, without any
 * request. Uses the shared [BrowseGenreIndexCache]; call off the main thread.
 */
fun browseGenresOf(apis: List<MainAPI>): List<Pair<String, Int>> =
    BrowseGenreIndexCache.get(apis.map { api ->
        object : BrowseSource {
            override val name = api.name
            override val lang = api.lang
            override val supportedTypes = api.supportedTypes
            override val hasMainPage = api.hasMainPage
            override val sectionNames = api.mainPage.map { it.name }
            override val requestDelayMs = 0L
            override suspend fun loadSection(sectionIndex: Int, page: Int): SectionPage =
                throw UnsupportedOperationException()
        }
    }).available
