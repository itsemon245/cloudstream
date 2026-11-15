package com.lagradost.cloudstream3.ui.player

import java.net.URI
import kotlin.math.abs

private const val SOURCE_SELECTION_PREFERENCE_FOLDER = "player_source_selection"
private const val SOURCE_SELECTION_HISTORY_FOLDER = "player_source_history"
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

/** `Qualities.Unknown.value`; providers use it when they do not know the resolution. */
private const val UNKNOWN_QUALITY = 400

private val SOURCE_RESOLUTION_P_PATTERN =
    Regex("""(?<![\p{L}\p{N}])(\d{3,4})[pi](?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
private val SOURCE_RESOLUTION_K_PATTERN =
    Regex("""(?<![\p{L}\p{N}])([248])k(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
private val SOURCE_RESOLUTION_TOKEN_PATTERN =
    Regex("""(?:\d{3,4}[pi]|[248]k)""", RegexOption.IGNORE_CASE)

/**
 * Every resolution a source label states, as the user sees it in the source dialog
 * (`"${link.name} ${Qualities.getStringByInt(link.quality)}"`):
 * - `NNNp`/`NNNi` tokens and `2K`/`4K`/`8K` in the name, and
 * - the `quality` field when it is a real resolution (not Unknown(400) or Auto(0)).
 *
 * An empty set means "no stated resolution", which is its own class: it only matches other
 * sources without a stated resolution. Two sources have the same resolution only when the sets
 * are equal, so a name that says `4K` never matches one that says nothing or `1080p`.
 */
internal fun sourceResolutions(name: String?, quality: Int?): Set<Int> {
    val resolutions = mutableSetOf<Int>()
    if (name != null) {
        SOURCE_RESOLUTION_P_PATTERN.findAll(name).forEach { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it in 144..4320 }?.let(resolutions::add)
        }
        SOURCE_RESOLUTION_K_PATTERN.findAll(name).forEach { match ->
            when (match.groupValues[1]) {
                "2" -> resolutions.add(1440)
                "4" -> resolutions.add(2160)
                "8" -> resolutions.add(4320)
            }
        }
    }
    if (quality != null && quality != UNKNOWN_QUALITY && quality in 144..4320) {
        resolutions.add(quality)
    }
    return resolutions
}

/**
 * The mirror family of a source: the first word of its name, ignoring resolution tokens
 * (falls back to `source` when the name has none), lowercased.
 *
 * `Vidy [Seattle]` and `Vidy [Atlanta]` -> `vidy`; `Atlantic [Lyra]` -> `atlantic`;
 * `DahmerMovies ][EAC3 5 1][x265]-iVy` and `DahmerMovies BluRay REMUX ...` -> `dahmermovies`.
 */
internal fun sourceFamily(name: String?, source: String?): String? {
    fun String?.firstWord(): String? = this?.let(SOURCE_NAME_TOKEN_PATTERN::findAll)
        ?.map { it.value.lowercase() }
        ?.firstOrNull { !SOURCE_RESOLUTION_TOKEN_PATTERN.matches(it) }
    return name.firstWord() ?: source.firstWord()
}

internal fun subtitleSelectionSource(url: String): String? = runCatching {
    URI(url).host.selectionKey()?.removePrefix("www.")
}.getOrNull()

internal fun sourceSelectionStorageKey(
    account: String,
    apiName: String,
    parentId: Int,
): String = "$account/$SOURCE_SELECTION_PREFERENCE_FOLDER/$apiName|$parentId"

/** Same scope as [sourceSelectionStorageKey] (account + provider + series), for the history. */
internal fun sourceSelectionHistoryStorageKey(
    account: String,
    apiName: String,
    parentId: Int,
): String = "$account/$SOURCE_SELECTION_HISTORY_FOLDER/$apiName|$parentId"

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
    private val family = sourceFamily(nameKey, sourceKey)
    private val resolutions = sourceResolutions(nameKey, quality)

    /** Two selections are the same pick when the user saw the same source. */
    fun isSamePick(other: SourceSelectionPreference): Boolean =
        sourceKey == other.sourceKey && nameKey == other.nameKey && quality == other.quality

    /** Whether [candidate] states exactly the same resolution(s) as this selection. */
    fun hasSameResolution(name: String?, quality: Int?): Boolean =
        sourceResolutions(name.selectionKey(), quality) == resolutions

    /** Same name at the same resolution; a matching source field breaks ties. */
    fun <T> findExact(
        candidates: Iterable<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? {
        val nameKey = nameKey ?: return null
        val exact = candidates.filter {
            name(it).selectionKey() == nameKey && hasSameResolution(name(it), quality(it))
        }
        return exact.firstOrNull { sourceKey != null && source(it).selectionKey() == sourceKey }
            ?: exact.firstOrNull()
    }

    /**
     * Another mirror of the same family at the same resolution. Among several, prefer a matching
     * source field, then the most shared release traits, then the closest file size, then the
     * candidate order (quality profile priority).
     */
    fun <T> findMirror(
        candidates: Iterable<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? {
        val family = family ?: return null
        var best: T? = null
        var bestSourceRank = -1
        var bestTraitOverlap = -1
        var bestFileSizeDistance = Double.POSITIVE_INFINITY

        for (candidate in candidates) {
            val candidateName = name(candidate).selectionKey()
            if (sourceFamily(candidateName, source(candidate).selectionKey()) != family) continue
            if (!hasSameResolution(candidateName, quality(candidate))) continue

            val sourceRank =
                if (sourceKey != null && source(candidate).selectionKey() == sourceKey) 1 else 0
            val traitOverlap = nameTraits.intersect(candidateName.sourceNameTraits()).size
            val candidateFileSizeDistance = fileSizeDistance(
                preferred = fileSizeInMb,
                candidate = candidateName.sourceFileSizeInMb(),
            )

            if (sourceRank > bestSourceRank ||
                sourceRank == bestSourceRank && traitOverlap > bestTraitOverlap ||
                sourceRank == bestSourceRank && traitOverlap == bestTraitOverlap &&
                candidateFileSizeDistance < bestFileSizeDistance
            ) {
                best = candidate
                bestSourceRank = sourceRank
                bestTraitOverlap = traitOverlap
                bestFileSizeDistance = candidateFileSizeDistance
            }
        }
        return best
    }

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
        /** How many recent picks are remembered per series. */
        const val LIMIT = 6

        val EMPTY = SourceSelectionHistory(emptyList())

        /** Restores the pre-history single selection (older app versions). */
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

    /** Moves the pick to the front (deduplicated) and keeps the [LIMIT] most recent picks. */
    fun remember(source: String?, name: String?, quality: Int?): SourceSelectionHistory {
        val preference = SourceSelectionPreference.create(source, name, quality) ?: return this
        return SourceSelectionHistory(
            (listOf(preference) + entries.filterNot(preference::isSamePick)).take(LIMIT)
        )
    }

    /**
     * The remembered source to start with, or null when normal priority should be used:
     * 1. for each remembered pick, newest first: the exact source, else a same-family mirror at
     *    the same resolution;
     * 2. the previous single-pick behaviour for the newest pick (same source field / same
     *    quality anywhere), restricted to the same stated resolution.
     */
    fun <T> findPreferred(
        candidates: List<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? {
        for (preference in entries) {
            preference.findExact(candidates, source, name, quality)?.let { return it }
            preference.findMirror(candidates, source, name, quality)?.let { return it }
        }
        val preference = mostRecent ?: return null
        val sameResolution = candidates.filter { preference.hasSameResolution(name(it), quality(it)) }
        return preference.findBest(sameResolution, source, name, quality)
            ?: preference.findSameQuality(sameResolution, quality)
    }

    /**
     * A remembered source good enough to start before all links finished loading. Deliberately
     * conservative (the newest pick's exact release only), because a mirror that arrives early
     * must not pre-empt the exact source that may still be loading; once loading completes
     * [findPreferred] applies the full fallback.
     */
    fun <T> findReady(
        candidates: List<T>,
        source: (T) -> String?,
        name: (T) -> String?,
        quality: (T) -> Int?,
    ): T? {
        val preference = mostRecent ?: return null
        return preference.findReady(
            candidates.filter { preference.hasSameResolution(name(it), quality(it)) },
            source, name, quality,
        )
    }

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
