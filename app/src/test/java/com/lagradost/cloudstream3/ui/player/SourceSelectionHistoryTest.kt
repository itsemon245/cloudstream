package com.lagradost.cloudstream3.ui.player

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Startup source choice for the next episode of a series.
 *
 * Candidate lists are given in quality-profile priority order (highest first), exactly as the
 * player hands them over, so `candidates.first()` is CloudStream's default pick.
 *
 * Names and qualities mirror what the source dialog shows: the label is
 * `"${link.name} ${Qualities.getStringByInt(link.quality)}"`, so `Vidy [Seattle]` is a link named
 * `Vidy [Seattle]` with an Unknown (400) quality and `Vidy [Atlanta] 720p` is named
 * `Vidy [Atlanta]` with quality 720. Providers often set `source == name` for each mirror, which
 * is the case these tests model unless stated otherwise.
 */
class SourceSelectionHistoryTest {
    private data class Link(
        val name: String,
        val quality: Int,
        val source: String = name,
    )

    private companion object {
        const val UNKNOWN = 400
    }

    private fun SourceSelectionHistory.remember(link: Link) =
        remember(source = link.source, name = link.name, quality = link.quality)

    private fun history(vararg oldestToNewest: Link): SourceSelectionHistory =
        oldestToNewest.fold(SourceSelectionHistory.EMPTY) { acc, link -> acc.remember(link) }

    private fun SourceSelectionHistory.pick(candidates: List<Link>): Link? =
        pick(candidates, Link::source, Link::name, Link::quality)

    private fun SourceSelectionHistory.findPreferred(candidates: List<Link>): Link? =
        findPreferred(candidates, Link::source, Link::name, Link::quality)

    private fun SourceSelectionHistory.findReady(candidates: List<Link>): Link? =
        findReady(candidates, Link::source, Link::name, Link::quality)

    private val dahmerIvy = Link("DahmerMovies ][EAC3 5 1][x265]-iVy", 1080)
    private val dahmerNorvine = Link("DahmerMovies Proper][EAC3 5 1][h265]-NORViNE", 1080)
    private val dahmerRemux = Link("DahmerMovies BluRay REMUX VC-1 DD 5 1-NOGRP", 1080)
    private val vidlink1080 = Link("Vidlink", 1080)
    private val vidlink720 = Link("Vidlink", 720)
    private val atlanticLyra = Link("Atlantic [Lyra]", 1080)
    private val atlanticAbigail = Link("Atlantic [Abigail]", 1080)
    private val atlanticSilvia720 = Link("Atlantic [Silvia]", 720)
    private val vidySeattle = Link("Vidy [Seattle]", UNKNOWN)
    private val vidyChicago = Link("Vidy [Chicago]", UNKNOWN)
    private val vidyPortland = Link("Vidy [Portland]", UNKNOWN)
    private val vidyAtlanta = Link("Vidy [Atlanta]", UNKNOWN)
    private val vidyAtlanta720 = Link("Vidy [Atlanta]", 720)

    // ---- 1. Exact match --------------------------------------------------------------------

    @Test
    fun `exact previous source wins over the default priority order`() {
        val result = history(vidyChicago).pick(
            listOf(dahmerIvy, vidlink1080, vidySeattle, vidyChicago)
        )

        assertEquals(vidyChicago, result)
    }

    @Test
    fun `exact name at a different resolution is not an exact match`() {
        // Previously "Vidy [Atlanta] 720p"; the next episode only has "Vidy [Atlanta]" (no
        // resolution) and a 720p mirror of the same family.
        val vidyNashville720 = Link("Vidy [Nashville]", 720)
        val result = history(vidyAtlanta720).pick(
            listOf(dahmerIvy, vidyAtlanta, vidyNashville720)
        )

        assertEquals(vidyNashville720, result)
    }

    // ---- 2. Same-family mirror at the same resolution --------------------------------------

    @Test
    fun `missing mirror falls back to another mirror of the same family without resolution`() {
        // Another provider also has links without a stated resolution and is ranked first.
        val otherUnknown = Link("Vidsrc [Main]", UNKNOWN)
        val result = history(vidyAtlanta).pick(
            listOf(dahmerIvy, otherUnknown, vidlink1080, vidySeattle, vidyChicago)
        )

        assertEquals(vidySeattle, result)
    }

    @Test
    fun `missing 1080p mirror falls back to a 1080p mirror of the same family`() {
        val result = history(atlanticLyra).pick(
            listOf(dahmerIvy, vidlink1080, atlanticSilvia720, atlanticAbigail)
        )

        assertEquals(atlanticAbigail, result)
    }

    @Test
    fun `release names of one provider form a family and the closest release wins`() {
        // Both remaining DahmerMovies releases are 1080p; the EAC3 one shares more release traits
        // with the previous pick than the REMUX one, even though it is ranked lower.
        val result = history(dahmerNorvine).pick(
            listOf(vidlink1080, atlanticLyra, dahmerRemux, dahmerIvy)
        )

        assertEquals(dahmerIvy, result)
    }

    @Test
    fun `resolution stated in the name equals the same resolution in the quality field`() {
        // "Vidy [Atlanta] 1080p" written in the name with an Unknown quality field is the same
        // resolution as a mirror whose quality field says 1080.
        val atlantaNamed1080 = Link("Vidy [Atlanta] 1080p", UNKNOWN)
        val chicago1080 = Link("Vidy [Chicago]", 1080)
        val result = history(atlantaNamed1080).pick(
            listOf(dahmerIvy, vidySeattle, chicago1080)
        )

        assertEquals(chicago1080, result)
    }

    @Test
    fun `family match also works when the provider sets a shared source field`() {
        val seattle = vidySeattle.copy(source = "Vidy")
        val chicago = vidyChicago.copy(source = "Vidy")
        val atlanta = vidyAtlanta.copy(source = "Vidy")
        val result = history(atlanta).pick(listOf(dahmerIvy, seattle, chicago))

        assertEquals(seattle, result)
    }

    // ---- Hard rule: never a different stated resolution ----------------------------------

    @Test
    fun `never falls back to a family mirror whose name states 4K`() {
        val atlanta4kInName = Link("Vidy [Atlanta] 4K", UNKNOWN)
        val result = history(vidyAtlanta).findPreferred(
            listOf(vidlink1080, atlanta4kInName)
        )

        assertNull(result)
    }

    @Test
    fun `never falls back to a family mirror whose quality field is 4K`() {
        val atlanta4k = Link("Vidy [Atlanta]", 2160)
        val result = history(vidyAtlanta).pick(listOf(vidlink1080, atlanta4k))

        // Nothing remembered fits, so the default (first) candidate is used.
        assertEquals(vidlink1080, result)
    }

    @Test
    fun `never falls back from 1080p to a 720p mirror of the same family`() {
        val result = history(atlanticLyra).findPreferred(
            listOf(atlanticSilvia720, vidyChicago)
        )

        assertNull(result)
    }

    @Test
    fun `a mirror without a stated resolution does not replace a 1080p pick`() {
        val atlanticNoResolution = Link("Atlantic [Silvia]", UNKNOWN)
        val result = history(atlanticLyra).findPreferred(
            listOf(atlanticNoResolution, vidySeattle)
        )

        assertNull(result)
    }

    // ---- 3. History ------------------------------------------------------------------------

    @Test
    fun `second most recent pick is used when the latest family is missing`() {
        val result = history(atlanticLyra, vidyAtlanta).pick(
            listOf(dahmerIvy, vidlink720, atlanticAbigail)
        )

        assertEquals(atlanticAbigail, result)
    }

    @Test
    fun `third most recent pick is used when the two latest are missing`() {
        val result = history(vidlink720, atlanticLyra, vidyAtlanta).pick(
            listOf(dahmerIvy, atlanticSilvia720, vidlink1080, vidlink720)
        )

        assertEquals(vidlink720, result)
    }

    @Test
    fun `most recent pick wins when several remembered sources are available`() {
        val result = history(atlanticLyra, vidyAtlanta).pick(
            listOf(dahmerIvy, atlanticLyra, vidyAtlanta)
        )

        assertEquals(vidyAtlanta, result)
    }

    @Test
    fun `a mirror of the latest pick wins over an exact match of an older pick`() {
        val result = history(atlanticLyra, vidyAtlanta).pick(
            listOf(dahmerIvy, atlanticLyra, vidyPortland)
        )

        assertEquals(vidyPortland, result)
    }

    @Test
    fun `history keeps the six most recent distinct picks, newest first`() {
        val picks = listOf(
            vidlink1080, vidlink720, atlanticLyra, atlanticAbigail,
            vidySeattle, vidyChicago, vidyAtlanta,
        )
        val result = history(*picks.toTypedArray()).remember(vidlink720)

        assertEquals(
            listOf(vidlink720, vidyAtlanta, vidyChicago, vidySeattle, atlanticAbigail, atlanticLyra)
                .map { it.name.lowercase() to it.quality },
            result.toStored().entries.map { it.name to it.quality },
        )
    }

    @Test
    fun `stored history restores every remembered pick in order`() {
        val original = history(vidlink720, atlanticLyra, vidyAtlanta)
        val restored = SourceSelectionHistory.restore(original.toStored())

        assertEquals(original.toStored(), restored.toStored())
        assertEquals(3, restored.toStored().entries.size)
    }

    // ---- 4. Default ------------------------------------------------------------------------

    @Test
    fun `default priority order is used when nothing remembered fits`() {
        val result = history(vidlink720, atlanticLyra, vidyAtlanta).pick(
            listOf(dahmerIvy, dahmerRemux)
        )

        assertEquals(dahmerIvy, result)
    }

    @Test
    fun `default pick wins over a lower-ranked same-quality link of another provider`() {
        // Nothing of the Atlantic family is left; the default (first) candidate must be used,
        // not the first 1080p link of an unrelated provider.
        assertEquals(vidySeattle, history(atlanticLyra).pick(listOf(vidySeattle, dahmerIvy)))
    }

    @Test
    fun `default pick wins over a lower-ranked link of another provider without resolution`() {
        val otherUnknown = Link("Vidsrc [Main]", UNKNOWN)
        assertEquals(vidlink1080, history(vidyAtlanta).pick(listOf(vidlink1080, otherUnknown)))
    }

    @Test
    fun `default priority order is used without any history`() {
        val result = SourceSelectionHistory.EMPTY.pick(listOf(vidlink1080, vidyAtlanta))

        assertEquals(vidlink1080, result)
    }

    // ---- Early start while links are still loading ----------------------------------------

    @Test
    fun `a same-family mirror is not ready while the exact source may still arrive`() {
        assertNull(history(vidyAtlanta).findReady(listOf(vidySeattle)))
    }

    @Test
    fun `the exact source is ready before all links finished loading`() {
        assertEquals(vidyAtlanta, history(vidyAtlanta).findReady(listOf(vidySeattle, vidyAtlanta)))
    }

    // ---- Pure helpers and storage ----------------------------------------------------------

    @Test
    fun `family is the first word of the name`() {
        assertEquals("vidy", sourceFamily("Vidy [Atlanta]", "Vidy [Atlanta]"))
        assertEquals("atlantic", sourceFamily("Atlantic [Lyra]", null))
        assertEquals("dahmermovies", sourceFamily(dahmerIvy.name, null))
        assertEquals("dahmermovies", sourceFamily(dahmerNorvine.name, null))
        assertEquals("dahmermovies", sourceFamily(dahmerRemux.name, null))
        assertEquals("vidlink", sourceFamily("Vidlink", null))
        assertEquals("vidy", sourceFamily("4K Vidy [Atlanta]", null))
        assertEquals("vidy", sourceFamily(null, "Vidy"))
    }

    @Test
    fun `resolution comes from the name and a real quality field`() {
        assertEquals(emptySet<Int>(), sourceResolutions("Vidy [Atlanta]", UNKNOWN))
        assertEquals(emptySet<Int>(), sourceResolutions("Vidy [Atlanta]", 0))
        assertEquals(setOf(2160), sourceResolutions("Vidy [Atlanta] 4K", UNKNOWN))
        assertEquals(setOf(2160), sourceResolutions("Vidy [Atlanta]", 2160))
        assertEquals(setOf(1080), sourceResolutions("Atlantic [Lyra] 1080p", 1080))
        assertEquals(setOf(720, 1080), sourceResolutions("Atlantic [Lyra] 720p", 1080))
        // Codec, audio and size tokens are not resolutions.
        assertEquals(setOf(1080), sourceResolutions(dahmerIvy.name, 1080))
        assertEquals(
            setOf(1080),
            sourceResolutions("Waiting.Hai.S01E03.1080p.AMZN.DDP5.1.x264[2.41GB]", UNKNOWN),
        )
    }

    @Test
    fun `resolution inside longer tokens is still a stated resolution`() {
        assertEquals(setOf(1080), sourceResolutions("Vidy [Chicago] 1080p60", UNKNOWN))
        assertEquals(setOf(2160), sourceResolutions("Vidy [Chicago] 2160p10", UNKNOWN))
        assertEquals(setOf(2160), sourceResolutions("Vidy [Chicago] 4KHDR", UNKNOWN))
        assertEquals(setOf(1080), sourceResolutions("Vidy [Chicago] HD1080p", UNKNOWN))
        assertEquals(setOf(2160), sourceResolutions("Vidy [Chicago] UHD", UNKNOWN))
        assertEquals(setOf(1080), sourceResolutions("Vidy [Chicago] FHD", UNKNOWN))
        assertEquals(emptySet<Int>(), sourceResolutions("Vidy [Chicago] HD", UNKNOWN))
        assertEquals(emptySet<Int>(), sourceResolutions("Vidy [Chicago] HDR x265", UNKNOWN))
        assertEquals("vidy", sourceFamily("1080p60 Vidy [Chicago]", null))
        assertEquals("vidy", sourceFamily("UHD Vidy [Chicago]", null))
    }

    @Test
    fun `never falls back to a mirror whose resolution sits in a longer token`() {
        val chicago1080p60 = Link("Vidy [Chicago] 1080p60", UNKNOWN)
        assertEquals(vidlink1080, history(vidyAtlanta).pick(listOf(vidlink1080, chicago1080p60)))
    }

    private fun stored(link: Link) =
        SourceSelectionPreference.create(link.source, link.name, link.quality)!!.toStored()

    @Test
    fun `restore prefers the stored history when the legacy key agrees`() {
        val stored = history(atlanticLyra, vidyAtlanta).toStored()
        val restored = SourceSelectionHistory.restore(stored, legacy = stored(vidyAtlanta))

        assertEquals(stored, restored.toStored())
        assertEquals(stored, SourceSelectionHistory.restore(stored, legacy = null).toStored())
    }

    @Test
    fun `restore uses the legacy key when there is no history`() {
        val restored = SourceSelectionHistory.restore(null, legacy = stored(vidyAtlanta))

        assertEquals(history(vidyAtlanta).toStored(), restored.toStored())
    }

    @Test
    fun `a newer legacy pick from an older app version goes on top of the history`() {
        // History written by this version, then a downgrade where the user picked Vidy, then an
        // upgrade again: the older build only updated the legacy key.
        val stored = history(vidlink720, atlanticLyra).toStored()
        val restored = SourceSelectionHistory.restore(stored, legacy = stored(vidyAtlanta))

        assertEquals(history(vidlink720, atlanticLyra, vidyAtlanta).toStored(), restored.toStored())
    }

    @Test
    fun `stored history survives the json mapper used by the data store`() {
        val stored = history(vidlink720, atlanticLyra, vidyAtlanta).toStored()
        val json = mapper.writeValueAsString(stored)

        assertEquals(stored, mapper.readValue<StoredSourceSelectionHistory>(json))
    }

    @Test
    fun `single selection stored by older versions becomes a one entry history`() {
        val legacy = SourceSelectionPreference.create("Vidy [Atlanta]", "Vidy [Atlanta]", UNKNOWN)
            ?.toStored()
        val restored = SourceSelectionHistory.restore(legacy)

        assertEquals(vidySeattle, restored.pick(listOf(dahmerIvy, vidySeattle)))
    }
}
