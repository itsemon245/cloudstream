package com.lagradost.cloudstream3.ui.player

import java.net.URI
import kotlin.math.abs

private const val SOURCE_SELECTION_PREFERENCE_FOLDER = "player_source_selection"
private const val READY_SOURCE_MAX_FILE_SIZE_DISTANCE = 0.5

private val SOURCE_FILE_SIZE_PATTERN =
    Regex("""(\d+(?:[.,]\d+)?)\s*(kb|mb|gb|tb)\b""", RegexOption.IGNORE_CASE)
private val SOURCE_NAME_TOKEN_PATTERN = Regex("""[\p{L}\p{N}]+""")
private val SOURCE_EPISODE_TOKEN_PATTERN =
    Regex("""(?:s\d{1,3}e\d{1,4}|(?:ep|episode)\d{1,4})""", RegexOption.IGNORE_CASE)

private fun String?.selectionKey(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.lowercase()

private fun String?.sourceNameTraits(): Set<String> {
    val name = this ?: return emptySet()
    val nameWithoutFileSize = SOURCE_FILE_SIZE_PATTERN.replace(name, " ")
    return SOURCE_NAME_TOKEN_PATTERN.findAll(nameWithoutFileSize)
        .map { it.value.lowercase() }
        .filterNot { token ->
            token.all(Char::isDigit) || SOURCE_EPISODE_TOKEN_PATTERN.matches(token)
        }
        .toSet()
}

private fun String?.sourceFileSizeInMb(): Double? {
    val match = this?.let(SOURCE_FILE_SIZE_PATTERN::findAll)?.lastOrNull() ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].lowercase()) {
        "kb" -> 1.0 / 1024.0
        "mb" -> 1.0
        "gb" -> 1024.0
        "tb" -> 1024.0 * 1024.0
        else -> return null
    }
    return value * multiplier
}

private fun fileSizeDistance(preferred: Double?, candidate: Double?): Double {
    if (preferred == null || candidate == null || preferred <= 0.0) {
        return Double.POSITIVE_INFINITY
    }
    return abs(candidate - preferred) / preferred
}

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

/** Persisted [SourceSelectionHistory], most recent selection first. */
internal data class StoredSourceSelectionHistory(
    val entries: List<StoredSourceSelection> = emptyList(),
)

internal class SourceSelectionPreference private constructor(
    private val sourceKey: String?,
    private val nameKey: String?,
    private val quality: Int?,
) {
    private val nameTraits = nameKey.sourceNameTraits()
    private val fileSizeInMb = nameKey.sourceFileSizeInMb()

    private fun isReadyName(candidateName: String?): Boolean {
        val candidateNameKey = candidateName.selectionKey()
        if (nameKey != null && candidateNameKey == nameKey) return true
        if (nameTraits.isEmpty() || candidateNameKey.sourceNameTraits() != nameTraits) return false

        return fileSizeInMb == null || fileSizeDistance(
            preferred = fileSizeInMb,
            candidate = candidateNameKey.sourceFileSizeInMb(),
        ) <= READY_SOURCE_MAX_FILE_SIZE_DISTANCE
    }

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
        var bestTraitOverlap = -1
        var bestFileSizeDistance = Double.POSITIVE_INFINITY

        for (candidate in candidates) {
            if (this.quality != null && quality(candidate) != this.quality) continue

            val sourceMatches = sourceKey != null && source(candidate).selectionKey() == sourceKey
            val candidateName = name(candidate).selectionKey()
            val nameMatches = nameKey != null && candidateName == nameKey
            val matchRank = when {
                sourceMatches && nameMatches -> 3
                sourceMatches -> 2
                nameMatches -> 1
                else -> continue
            }
            val traitOverlap = nameTraits.intersect(candidateName.sourceNameTraits()).size
            val candidateFileSizeDistance = fileSizeDistance(
                preferred = fileSizeInMb,
                candidate = candidateName.sourceFileSizeInMb(),
            )

            if (matchRank > bestMatchRank ||
                matchRank == bestMatchRank && traitOverlap > bestTraitOverlap ||
                matchRank == bestMatchRank && traitOverlap == bestTraitOverlap &&
                candidateFileSizeDistance < bestFileSizeDistance
            ) {
                best = candidate
                bestMatchRank = matchRank
                bestTraitOverlap = traitOverlap
                bestFileSizeDistance = candidateFileSizeDistance
            }
        }

        return best
    }

    fun <T> findReady(
        candidates: Iterable<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? = findBest(
        candidates = candidates.filter { isReadyName(name(it)) },
        source = source,
        name = name,
        quality = quality,
    )

    fun <T> findSameQuality(
        candidates: Iterable<T>,
        quality: (T) -> Int?,
    ): T? {
        val preferredQuality = this.quality ?: return null
        return candidates.firstOrNull { quality(it) == preferredQuality }
    }
}

/**
 * Ordered (most recent first) list of the sources the user picked for one series, and the
 * pure selection logic the player uses to choose the startup link of the next episode.
 *
 * Candidates passed to [pick]/[findPreferred]/[findReady] must already be filtered to usable
 * links and sorted by the quality profile priority (highest first), exactly like
 * `VideoState.sortLinks(profile).filter { it.shouldUseLink }`.
 */
internal class SourceSelectionHistory private constructor(
    val entries: List<SourceSelectionPreference>,
) {
    companion object {
        /** Current behaviour: only the latest selection is remembered. */
        const val LIMIT = 1

        val EMPTY = SourceSelectionHistory(emptyList())

        fun restore(stored: StoredSourceSelection?): SourceSelectionHistory =
            SourceSelectionPreference.restore(stored)
                ?.let { SourceSelectionHistory(listOf(it)) }
                ?: EMPTY

        fun restore(stored: StoredSourceSelectionHistory?): SourceSelectionHistory =
            SourceSelectionHistory(
                stored?.entries.orEmpty()
                    .mapNotNull(SourceSelectionPreference::restore)
                    .take(LIMIT)
            )
    }

    fun toStored(): StoredSourceSelectionHistory =
        StoredSourceSelectionHistory(entries.map(SourceSelectionPreference::toStored))

    val mostRecent: SourceSelectionPreference? get() = entries.firstOrNull()

    fun isEmpty(): Boolean = entries.isEmpty()

    fun remember(source: String?, name: String?, quality: Int?): SourceSelectionHistory {
        val preference = SourceSelectionPreference.create(source, name, quality) ?: return this
        return SourceSelectionHistory(listOf(preference))
    }

    /** The remembered source to start with, or null when normal priority should be used. */
    fun <T> findPreferred(
        candidates: List<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? {
        val preference = mostRecent ?: return null
        return preference.findBest(candidates, source, name, quality)
            ?: preference.findSameQuality(candidates, quality)
    }

    /** A remembered source that is good enough to start before all links finished loading. */
    fun <T> findReady(
        candidates: List<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? = mostRecent?.findReady(candidates, source, name, quality)

    /** Full startup choice: remembered source, otherwise the highest priority candidate. */
    fun <T> pick(
        candidates: List<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? = findPreferred(candidates, source, name, quality) ?: candidates.firstOrNull()
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
