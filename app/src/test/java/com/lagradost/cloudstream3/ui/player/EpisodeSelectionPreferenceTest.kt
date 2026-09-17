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
