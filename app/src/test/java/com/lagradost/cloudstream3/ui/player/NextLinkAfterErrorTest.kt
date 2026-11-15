package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which link the player switches to when the playing link fails (e.g. an
 * `ERROR_CODE_IO_BAD_HTTP_STATUS (2004)` source error).
 *
 * Candidate lists are in quality-profile priority order, like `VideoState.sortLinks`. Names and
 * qualities follow the source dialog label `"${link.name} ${quality}"`, see
 * [SourceSelectionHistoryTest].
 */
class NextLinkAfterErrorTest {
    private data class Link(
        val name: String,
        val quality: Int,
        val source: String = name,
        val usable: Boolean = true,
        val url: String = "$name/$quality",
    )

    private companion object {
        const val UNKNOWN = 400
    }

    private val dahmerIvy = Link("DahmerMovies ][EAC3 5 1][x265]-iVy", 1080)
    private val dahmerNorvine = Link("DahmerMovies Proper][EAC3 5 1][h265]-NORViNE", 1080)
    private val vidlink1080 = Link("Vidlink", 1080)
    private val atlanticLyra = Link("Atlantic [Lyra]", 1080)
    private val atlanticAbigail = Link("Atlantic [Abigail]", 1080)
    private val atlanticSilvia720 = Link("Atlantic [Silvia]", 720)
    private val vidySeattle = Link("Vidy [Seattle]", UNKNOWN)
    private val vidyChicago = Link("Vidy [Chicago]", UNKNOWN)
    private val vidyAtlanta = Link("Vidy [Atlanta]", UNKNOWN)
    private val vidyAtlanta4k = Link("Vidy [Atlanta] 4K", UNKNOWN)

    private fun next(
        candidates: List<Link>,
        current: Link?,
        errored: Set<Link> = setOfNotNull(current),
    ): Link? = nextLinkAfterError(
        candidates = candidates,
        isCurrent = { it == current },
        isUsable = Link::usable,
        hasErrored = { it in errored },
        source = Link::source,
        name = Link::name,
        quality = Link::quality,
    )

    /** Fails every link the player switches to and returns the order they were tried in. */
    private fun failEverything(candidates: List<Link>, start: Link): List<Link> {
        val tried = mutableListOf(start)
        val errored = mutableSetOf(start)
        var current = start
        while (true) {
            val nextLink = next(candidates, current, errored) ?: break
            check(tried.size <= candidates.size * 2) { "Switching never ends: $tried" }
            tried += nextLink
            errored += nextLink
            current = nextLink
        }
        return tried
    }

    // ---- Same-family mirror first ----------------------------------------------------------

    @Test
    fun `failed mirror switches to another mirror of the same family`() {
        val links = listOf(vidyAtlanta, dahmerIvy, vidlink1080, vidySeattle, vidyChicago)

        assertEquals(vidySeattle, next(links, current = vidyAtlanta))
    }

    @Test
    fun `failed mirror switches to a same-family mirror ranked above it`() {
        val links = listOf(vidlink1080, atlanticAbigail, dahmerIvy, atlanticLyra, vidySeattle)

        assertEquals(atlanticAbigail, next(links, current = atlanticLyra))
    }

    @Test
    fun `mirrors that already failed are skipped`() {
        val links = listOf(vidyAtlanta, dahmerIvy, vidySeattle, vidyChicago)

        val result = next(links, current = vidyAtlanta, errored = setOf(vidyAtlanta, vidySeattle))

        assertEquals(vidyChicago, result)
    }

    @Test
    fun `hidden mirrors are skipped`() {
        val hiddenSeattle = vidySeattle.copy(usable = false)
        val links = listOf(vidyAtlanta, dahmerIvy, hiddenSeattle, vidyChicago)

        assertEquals(vidyChicago, next(links, current = vidyAtlanta))
    }

    @Test
    fun `another link with the same name is a mirror too`() {
        val atlantaOtherServer = vidyAtlanta.copy(url = "other-server")
        val links = listOf(vidyAtlanta, dahmerIvy, vidySeattle, atlantaOtherServer)

        assertEquals(atlantaOtherServer, next(links, current = vidyAtlanta))
    }

    @Test
    fun `release of the same provider with the closest traits is preferred`() {
        val dahmerRemux = Link("DahmerMovies BluRay REMUX VC-1 DD 5 1-NOGRP", 1080)
        val links = listOf(dahmerNorvine, vidlink1080, dahmerRemux, dahmerIvy)

        assertEquals(dahmerIvy, next(links, current = dahmerNorvine))
    }

    // ---- Never a different stated resolution ----------------------------------------------

    @Test
    fun `failed mirror does not switch to a 4K mirror of the same family`() {
        val links = listOf(vidyAtlanta, dahmerIvy, vidyAtlanta4k)

        assertEquals(dahmerIvy, next(links, current = vidyAtlanta))
    }

    @Test
    fun `failed 1080p mirror does not switch to a 720p mirror of the same family`() {
        val links = listOf(atlanticLyra, vidlink1080, atlanticSilvia720)

        assertEquals(vidlink1080, next(links, current = atlanticLyra))
    }

    // ---- Unchanged order otherwise ---------------------------------------------------------

    @Test
    fun `without a same-family mirror the next link in priority order is used`() {
        val links = listOf(dahmerIvy, vidyAtlanta, vidlink1080, atlanticLyra)

        assertEquals(vidlink1080, next(links, current = vidyAtlanta))
    }

    @Test
    fun `without a current link the first usable link is used`() {
        val links = listOf(dahmerIvy.copy(usable = false), vidlink1080, vidyAtlanta)

        assertEquals(vidlink1080, next(links, current = null))
    }

    @Test
    fun `nothing is returned when the failed link was the last option`() {
        val links = listOf(dahmerIvy, vidlink1080, vidyAtlanta)

        assertNull(next(links, current = vidyAtlanta))
    }

    @Test
    fun `links that already failed are not retried in priority order`() {
        val links = listOf(vidyAtlanta, dahmerIvy, vidlink1080, atlanticLyra)

        val result = next(links, current = vidyAtlanta, errored = setOf(vidyAtlanta, dahmerIvy))

        assertEquals(vidlink1080, result)
    }

    @Test
    fun `every mirror of the family is tried before moving on, and switching ends`() {
        // The family is one block at the position of its highest-ranked mirror (Chicago), so
        // after all Vidy mirrors failed the player continues below Chicago and does not skip
        // DahmerMovies, which sits between the mirrors. Vidlink, ranked above the whole family,
        // is not tried, as before.
        val links = listOf(
            vidlink1080, vidyChicago, dahmerIvy, vidyAtlanta, atlanticLyra, vidySeattle,
        )

        assertEquals(
            listOf(vidyAtlanta, vidyChicago, vidySeattle, dahmerIvy, atlanticLyra),
            failEverything(links, start = vidyAtlanta),
        )
    }
}
