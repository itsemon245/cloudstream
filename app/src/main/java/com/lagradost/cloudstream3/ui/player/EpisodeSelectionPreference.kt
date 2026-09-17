package com.lagradost.cloudstream3.ui.player

import java.net.URI

private const val SOURCE_SELECTION_PREFERENCE_FOLDER = "player_source_selection"

private fun String?.selectionKey(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.lowercase()

internal fun subtitleSelectionSource(url: String): String? = runCatching {
    URI(url).host.selectionKey()?.removePrefix("www.")
}.getOrNull()

internal fun sourceSelectionStorageKey(
    account: String,
    apiName: String,
    parentId: Int,
): String = "$account/$SOURCE_SELECTION_PREFERENCE_FOLDER/$apiName|$parentId"

internal data class StoredSourceSelection(
    val source: String?,
    val name: String?,
    val quality: Int?,
)

internal class SourceSelectionPreference private constructor(
    private val sourceKey: String?,
    private val nameKey: String?,
    private val quality: Int?,
) {
    companion object {
        fun create(source: String?, name: String?, quality: Int?): SourceSelectionPreference? {
            val sourceKey = source.selectionKey()
            val nameKey = name.selectionKey()
            if (sourceKey == null && nameKey == null) return null

            return SourceSelectionPreference(sourceKey, nameKey, quality)
        }

        fun restore(stored: StoredSourceSelection?): SourceSelectionPreference? = stored?.let {
            create(it.source, it.name, it.quality)
        }
    }

    fun toStored(): StoredSourceSelection = StoredSourceSelection(sourceKey, nameKey, quality)

    fun <T> findBest(
        candidates: Iterable<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? {
        var best: T? = null
        var bestMatchRank = 0

        for (candidate in candidates) {
            if (this.quality != null && quality(candidate) != this.quality) continue

            val sourceMatches = sourceKey != null && source(candidate).selectionKey() == sourceKey
            val nameMatches = nameKey != null && name(candidate).selectionKey() == nameKey
            val matchRank = when {
                sourceMatches && nameMatches -> 3
                sourceMatches -> 2
                nameMatches -> 1
                else -> continue
            }

            if (matchRank > bestMatchRank) {
                best = candidate
                bestMatchRank = matchRank
            }
        }

        return best
    }

    fun <T> findSameQuality(
        candidates: Iterable<T>,
        quality: (T) -> Int?,
    ): T? {
        val preferredQuality = this.quality ?: return null
        return candidates.firstOrNull { quality(it) == preferredQuality }
    }
}

internal class SubtitleSelectionPreference private constructor(
    private val originalNameKey: String,
    private val nameSuffixKey: String?,
    private val originKey: String?,
    private val languageTagKey: String?,
    private val sourceKey: String?,
) {
    companion object {
        fun create(
            originalName: String,
            nameSuffix: String,
            origin: String,
            languageTag: String?,
            source: String?,
        ): SubtitleSelectionPreference? {
            val originalNameKey = originalName.selectionKey() ?: return null
            return SubtitleSelectionPreference(
                originalNameKey = originalNameKey,
                nameSuffixKey = nameSuffix.selectionKey(),
                originKey = origin.selectionKey(),
                languageTagKey = languageTag.selectionKey(),
                sourceKey = source.selectionKey(),
            )
        }
    }

    fun <T> findBest(
        candidates: Iterable<T>,
        originalName: (T) -> String,
        nameSuffix: (T) -> String,
        origin: (T) -> String,
        languageTag: (T) -> String?,
        source: (T) -> String?,
    ): T? {
        var best: T? = null
        var bestMatchRank = 0
        var bestLanguageRank = 0

        for (candidate in candidates) {
            if (originalName(candidate).selectionKey() != originalNameKey) continue

            val originMatches = originKey != null && origin(candidate).selectionKey() == originKey
            val suffixMatches =
                nameSuffixKey != null && nameSuffix(candidate).selectionKey() == nameSuffixKey
            val sourceMatches = sourceKey != null && source(candidate).selectionKey() == sourceKey
            val matchRank = when {
                sourceMatches && originMatches -> 6
                sourceMatches -> 5
                originMatches && suffixMatches -> 4
                originMatches -> 3
                suffixMatches -> 2
                else -> 1
            }
            val languageRank = if (
                languageTagKey != null && languageTag(candidate).selectionKey() == languageTagKey
            ) {
                1
            } else {
                0
            }

            if (matchRank > bestMatchRank ||
                matchRank == bestMatchRank && languageRank > bestLanguageRank
            ) {
                best = candidate
                bestMatchRank = matchRank
                bestLanguageRank = languageRank
            }
        }

        return best
    }
}
