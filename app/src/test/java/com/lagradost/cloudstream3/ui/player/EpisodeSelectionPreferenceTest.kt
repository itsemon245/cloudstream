package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeSelectionPreferenceTest {
    private data class SourceCandidate(
        val id: String,
        val source: String?,
        val name: String?,
        val quality: Int?,
    )

    private data class SubtitleCandidate(
        val id: String,
        val originalName: String,
        val nameSuffix: String,
        val origin: String,
        val languageTag: String?,
        val source: String? = null,
    )

    @Test
    fun `selected video source wins over the default candidate order`() {
        val default = SourceCandidate("default", "Vidstream", "Vidstream", 1080)
        val selected = SourceCandidate("selected", "Streamwish", "Streamwish", 720)
        val preference = SourceSelectionPreference.create(
            source = selected.source,
            name = selected.name,
            quality = selected.quality,
        )

        val result = preference?.findBest(
            candidates = listOf(default, selected),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertEquals("selected", result?.id)
    }

    @Test
    fun `source identity survives a changing display name`() {
        val preference = SourceSelectionPreference.create(
            source = "Streamwish",
            name = "Streamwish episode 1",
            quality = 1080,
        )
        val sameSource = SourceCandidate(
            "same-source",
            " streamWISH ",
            "Streamwish episode 2",
            1080,
        )

        val result = preference?.findBest(
            candidates = listOf(
                SourceCandidate("other", "Vidstream", "Vidstream", 1080),
                sameSource,
            ),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertEquals("same-source", result?.id)
    }

    @Test
    fun `missing video source returns null so normal priority can be used`() {
        val preference = SourceSelectionPreference.create("Streamwish", "Streamwish", 1080)

        val result = preference?.findBest(
            candidates = listOf(SourceCandidate("other", "Vidstream", "Vidstream", 1080)),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertNull(result)
    }

    @Test
    fun `same source family at a different quality falls back to normal priority`() {
        val preference = SourceSelectionPreference.create("Streamwish", "Streamwish 1080p", 1080)
        val candidates = listOf(
            SourceCandidate("worse-family", "Streamwish", "Streamwish 720p", 720),
            SourceCandidate("same-quality", "Vidstream", "Vidstream 1080p", 1080),
        )

        val result = preference?.findBest(
            candidates = candidates,
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )
        val qualityFallback = preference?.findSameQuality(
            candidates = candidates,
            quality = SourceCandidate::quality,
        )

        assertNull(result)
        assertEquals("same-quality", qualityFallback?.id)
    }

    @Test
    fun `same source and resolution preserves release traits instead of choosing first match`() {
        val preference = SourceSelectionPreference.create(
            source = "MoviesDrive [FSL Server]",
            name = "MoviesDrive [FSL Server] WEB-DL X264 DDP5.1 AMZN [2.38 GB] 1080p",
            quality = 1080,
        )
        val candidates = listOf(
            SourceCandidate(
                id = "smaller-first-match",
                source = "MoviesDrive [FSL Server]",
                name = "MoviesDrive [FSL Server] WEB-DL X264 [791.79 MB] 1080p",
                quality = 1080,
            ),
            SourceCandidate(
                id = "matching-release",
                source = "MoviesDrive [FSL Server]",
                name = "MoviesDrive [FSL Server] WEB-DL X264 DDP5.1 AMZN [2.41 GB] 1080p",
                quality = 1080,
            ),
        )

        val result = preference?.findBest(
            candidates = candidates,
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertEquals("matching-release", result?.id)
    }

    @Test
    fun `same release traits choose the file size closest to the previous episode`() {
        val preference = SourceSelectionPreference.create(
            source = "MoviesDrive [FSL Server]",
            name = "MoviesDrive [FSL Server] WEB-DL X264 [2.38 GB] 1080p",
            quality = 1080,
        )
        val candidates = listOf(
            SourceCandidate(
                id = "smaller-first-match",
                source = "MoviesDrive [FSL Server]",
                name = "MoviesDrive [FSL Server] WEB-DL X264 [791.79 MB] 1080p",
                quality = 1080,
            ),
            SourceCandidate(
                id = "similar-size",
                source = "MoviesDrive [FSL Server]",
                name = "MoviesDrive [FSL Server] WEB-DL X264 [2.41 GB] 1080p",
                quality = 1080,
            ),
        )

        val result = preference?.findBest(
            candidates = candidates,
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertEquals("similar-size", result?.id)
    }

    @Test
    fun `weaker family match is not ready while later links are still loading`() {
        val preference = SourceSelectionPreference.create(
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL X264 DDP5.1 AMZN [2.38 GB] 1080p",
            quality = 1080,
        )

        val result = preference?.findReady(
            candidates = listOf(
                SourceCandidate(
                    id = "early-smaller-link",
                    source = "MoviesDrive",
                    name = "MoviesDrive [FSL Server] WEB-DL X264 [791.79 MB] 1080p",
                    quality = 1080,
                ),
            ),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertNull(result)
    }

    @Test
    fun `matching release is ready before all links finish loading`() {
        val preference = SourceSelectionPreference.create(
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL X264 DDP5.1 AMZN [2.38 GB] 1080p",
            quality = 1080,
        )
        val matchingRelease = SourceCandidate(
            id = "matching-release",
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL X264 DDP5.1 AMZN [2.41 GB] 1080p",
            quality = 1080,
        )

        val result = preference?.findReady(
            candidates = listOf(matchingRelease),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertEquals("matching-release", result?.id)
    }

    @Test
    fun `different codec does not start before the selected release family arrives`() {
        val preference = SourceSelectionPreference.create(
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL X264 DDP5.1 AMZN [2.38 GB] 1080p",
            quality = 1080,
        )
        val earlyHevcLink = SourceCandidate(
            id = "early-hevc-link",
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL HEVC X265 [560.58 MB] 1080p",
            quality = 1080,
        )
        val matchingRelease = SourceCandidate(
            id = "matching-release",
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL X264 DDP5.1 AMZN [2.5 GB] 1080p",
            quality = 1080,
        )

        val earlyResult = preference?.findReady(
            candidates = listOf(earlyHevcLink),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )
        val completedResult = preference?.findBest(
            candidates = listOf(earlyHevcLink, matchingRelease),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertNull(earlyResult)
        assertEquals("matching-release", completedResult?.id)
    }

    @Test
    fun `four k selection preserves mirror codec and release traits across episodes`() {
        val preference = SourceSelectionPreference.create(
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL HEVC X265 DDP5.1 AMZN [4.12 GB] 4K",
            quality = 2160,
        )
        val alternateMirror = SourceCandidate(
            id = "pixeldrain",
            source = "MoviesDrive",
            name = "MoviesDrive [Pixeldrain] WEB-DL HEVC X265 DDP5.1 AMZN [3.8 GB] 4K",
            quality = 2160,
        )
        val matchingMirror = SourceCandidate(
            id = "fsl-server",
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL HEVC X265 DDP5.1 AMZN [3.8 GB] 4K",
            quality = 2160,
        )

        val earlyResult = preference?.findReady(
            candidates = listOf(alternateMirror),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )
        val completedResult = preference?.findBest(
            candidates = listOf(alternateMirror, matchingMirror),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertNull(earlyResult)
        assertEquals("fsl-server", completedResult?.id)
    }

    @Test
    fun `long filename keeps mirror and release while episode and views change`() {
        val preference = SourceSelectionPreference.create(
            source = "MoviesDrive",
            name = "MoviesDrive GDFlix [Cloud] Waiting.Hai.S01E03.1080p.AMZN.WEB-DL.Hindi.DDP5.1.ESub.x264-MoviesDrives.CFD.mkv[2.41GB | Views : 6]",
            quality = 1080,
        )
        val earlyDifferentRelease = SourceCandidate(
            id = "direct-smaller-release",
            source = "MoviesDrive",
            name = "MoviesDrive GDFlix [Direct] Waiting.Hai.S01E04.1080p.Hindi.WEB-DL.5.1.ESub.x264-MoviesDrives.CFD.mkv[729.87MB | Views : 7]",
            quality = 1080,
        )
        val matchingRelease = SourceCandidate(
            id = "cloud-matching-release",
            source = "MoviesDrive",
            name = "MoviesDrive GDFlix [Cloud] Waiting.Hai.S01E04.1080p.AMZN.WEB-DL.Hindi.DDP5.1.ESub.x264-MoviesDrives.CFD.mkv[2.31GB | Views : 5]",
            quality = 1080,
        )

        val earlyResult = preference?.findReady(
            candidates = listOf(earlyDifferentRelease),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )
        val readyResult = preference?.findReady(
            candidates = listOf(earlyDifferentRelease, matchingRelease),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertNull(earlyResult)
        assertEquals("cloud-matching-release", readyResult?.id)
    }

    @Test
    fun `matching four eighty release survives episode and size changes`() {
        val preference = SourceSelectionPreference.create(
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL X264 [136.71 MB] 480p",
            quality = 480,
        )
        val matchingRelease = SourceCandidate(
            id = "matching-480p",
            source = "MoviesDrive",
            name = "MoviesDrive [FSL Server] WEB-DL X264 [126.01 MB] 480p",
            quality = 480,
        )

        val result = preference?.findReady(
            candidates = listOf(matchingRelease),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertEquals("matching-480p", result?.id)
    }

    @Test
    fun `stored source preference restores the exact selection after player recreation`() {
        val original = SourceSelectionPreference.create("Streamwish", "Streamwish 1080p", 1080)
        val restored = SourceSelectionPreference.restore(original?.toStored())

        val result = restored?.findBest(
            candidates = listOf(
                SourceCandidate("default", "Vidstream", "Vidstream 1080p", 1080),
                SourceCandidate("selected", "Streamwish", "Streamwish 1080p", 1080),
            ),
            source = SourceCandidate::source,
            name = SourceCandidate::name,
            quality = SourceCandidate::quality,
        )

        assertEquals("selected", result?.id)
    }

    @Test
    fun `stored source preference is scoped to local account provider and series`() {
        val key = sourceSelectionStorageKey("0", "Provider A", 123)

        assertEquals(key, sourceSelectionStorageKey("0", "Provider A", 123))
        assertNotEquals(key, sourceSelectionStorageKey("1", "Provider A", 123))
        assertNotEquals(key, sourceSelectionStorageKey("0", "Provider B", 123))
        assertNotEquals(key, sourceSelectionStorageKey("0", "Provider A", 456))
    }

    @Test
    fun `selected subtitle variant is handed to the next episode`() {
        val preference = SubtitleSelectionPreference.create(
            originalName = "English - OpenSubtitles",
            nameSuffix = "2",
            origin = "URL",
            languageTag = "en",
            source = "subs.opensubtitles.org",
        )

        val result = preference?.findBest(
            candidates = listOf(
                SubtitleCandidate("first", "English - OpenSubtitles", "1", "URL", "en"),
                SubtitleCandidate(
                    "selected",
                    "English - OpenSubtitles",
                    "2",
                    "URL",
                    "en",
                    "subs.opensubtitles.org",
                ),
            ),
            originalName = SubtitleCandidate::originalName,
            nameSuffix = SubtitleCandidate::nameSuffix,
            origin = SubtitleCandidate::origin,
            languageTag = SubtitleCandidate::languageTag,
            source = SubtitleCandidate::source,
        )

        assertEquals("selected", result?.id)
    }

    @Test
    fun `same subtitle label is used when the old variant number is absent`() {
        val preference = SubtitleSelectionPreference.create(
            originalName = "English - OpenSubtitles",
            nameSuffix = "2",
            origin = "URL",
            languageTag = "en",
            source = null,
        )

        val result = preference?.findBest(
            candidates = listOf(
                SubtitleCandidate("replacement", "English - OpenSubtitles", "1", "URL", "en"),
            ),
            originalName = SubtitleCandidate::originalName,
            nameSuffix = SubtitleCandidate::nameSuffix,
            origin = SubtitleCandidate::origin,
            languageTag = SubtitleCandidate::languageTag,
            source = SubtitleCandidate::source,
        )

        assertEquals("replacement", result?.id)
    }

    @Test
    fun `language alone does not impersonate a selected subtitle source`() {
        val preference = SubtitleSelectionPreference.create(
            originalName = "English - OpenSubtitles",
            nameSuffix = "1",
            origin = "URL",
            languageTag = "en",
            source = null,
        )

        val result = preference?.findBest(
            candidates = listOf(
                SubtitleCandidate("different", "English - Embedded", "1", "URL", "en"),
            ),
            originalName = SubtitleCandidate::originalName,
            nameSuffix = SubtitleCandidate::nameSuffix,
            origin = SubtitleCandidate::origin,
            languageTag = SubtitleCandidate::languageTag,
            source = SubtitleCandidate::source,
        )

        assertNull(result)
    }

    @Test
    fun `subtitle source wins when duplicate numbering changes between episodes`() {
        val preference = SubtitleSelectionPreference.create(
            originalName = "English",
            nameSuffix = "2",
            origin = "URL",
            languageTag = "en",
            source = "subs.opensubtitles.org",
        )

        val result = preference?.findBest(
            candidates = listOf(
                SubtitleCandidate("old-number", "English", "2", "URL", "en", "other.example"),
                SubtitleCandidate(
                    "same-source",
                    "English",
                    "1",
                    "URL",
                    "en",
                    "subs.opensubtitles.org",
                ),
            ),
            originalName = SubtitleCandidate::originalName,
            nameSuffix = SubtitleCandidate::nameSuffix,
            origin = SubtitleCandidate::origin,
            languageTag = SubtitleCandidate::languageTag,
            source = SubtitleCandidate::source,
        )

        assertEquals("same-source", result?.id)
    }
}
