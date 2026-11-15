package com.lagradost.cloudstream3.ui.browse

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.ui.browse.engine.BrowseSource
import com.lagradost.cloudstream3.ui.browse.engine.SectionPage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

internal fun apiNamed(name: String) = object : MainAPI() {
    override var name = name
    override var mainUrl = "https://$name.test"
}

internal fun movie(
    source: String,
    name: String,
    year: Int? = null,
    score: Double? = null,
    quality: SearchQuality? = null,
    url: String = "/$name",
): SearchResponse = apiNamed(source).newMovieSearchResponse(name, url, TvType.Movie, fix = false) {
    this.year = year
    this.score = Score.from10(score)
    this.quality = quality
}

internal fun series(source: String, name: String, year: Int? = null): SearchResponse =
    apiNamed(source).newTvSeriesSearchResponse(name, "/$name", TvType.TvSeries, fix = false) {
        this.year = year
    }

internal fun anime(source: String, name: String, dub: DubStatus? = null, otherName: String? = null): SearchResponse =
    apiNamed(source).newAnimeSearchResponse(name, "/$name", TvType.Anime, fix = false) {
        this.otherName = otherName
        if (dub != null) addDubStatus(dub)
    }

/** Fake provider: [pages] maps section index -> list of pages. */
internal class FakeSource(
    override val name: String,
    override val sectionNames: List<String>,
    private val pages: Map<Int, List<List<SearchResponse>>> = emptyMap(),
    override val supportedTypes: Set<TvType> = setOf(TvType.Movie, TvType.TvSeries),
    override val hasMainPage: Boolean = true,
    override val lang: String = "en",
    override val requestDelayMs: Long = 0,
    private val latencyMs: Long = 0,
    private val failSections: Set<Int> = emptySet(),
    private val inFlightCounter: AtomicInteger? = null,
    private val maxInFlight: AtomicInteger? = null,
) : BrowseSource {
    val calls = AtomicInteger()
    private val ownInFlight = AtomicInteger()
    val maxOwnInFlight = AtomicInteger()

    override suspend fun loadSection(sectionIndex: Int, page: Int): SectionPage {
        calls.incrementAndGet()
        val now = inFlightCounter?.incrementAndGet() ?: 0
        maxInFlight?.updateAndGet { maxOf(it, now) }
        maxOwnInFlight.updateAndGet { maxOf(it, ownInFlight.incrementAndGet()) }
        try {
            if (latencyMs > 0) delay(latencyMs)
            if (sectionIndex in failSections) error("boom")
            val sectionPages = pages[sectionIndex].orEmpty()
            val items = sectionPages.getOrNull(page - 1).orEmpty()
            return SectionPage(items, hasNext = page < sectionPages.size)
        } finally {
            ownInFlight.decrementAndGet()
            inFlightCounter?.decrementAndGet()
        }
    }
}
