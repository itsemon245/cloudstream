package com.lagradost.cloudstream3.ui.browse.engine

/**
 * Pre-computed genres of every provider section, so planning, the genre lists and the source
 * statuses never re-parse section names. Built once per plugin load (off the main thread) and
 * then only read.
 *
 * Besides the canonical genres it "discovers" genres from section names that match none of them
 * (e.g. "Superhero Movies" -> "superhero"). A discovered genre is only offered when at least
 * [MIN_SOURCES_FOR_DISCOVERED] providers share it, which filters out one-off list names.
 */
class GenreIndex private constructor(
    private val bySource: Map<String, SourceEntry>,
    /** Genre keys offered to the user, with the number of providers that have them. */
    val available: List<Pair<String, Int>>,
) {
    private class SourceEntry(
        val signature: List<String>,
        /** Tokenised section names, index-aligned with the provider's sections. */
        val sectionTokens: List<List<String>>,
        /** Genre keys (canonical + discovered) of each section. */
        val sectionGenres: List<Set<String>>,
    )

    /** True when this index was built from exactly these providers and sections. */
    fun isFor(sources: List<BrowseSource>): Boolean =
        sources.size == bySource.size && sources.all { bySource[it.name]?.signature == signature(it) }

    /**
     * Section index -> matched genre keys for [source]. Canonical genres come from the index, any
     * other key (discovered, or ad-hoc from a detail page tag) is matched against cached tokens.
     */
    fun sectionsFor(source: BrowseSource, genres: Set<String>): Map<Int, Set<String>> {
        if (genres.isEmpty()) return emptyMap()
        val entry = bySource[source.name]
        val tokens = entry?.sectionTokens ?: source.sectionNames.map { GenreMatcher.tokens(it) }
        val result = LinkedHashMap<Int, Set<String>>()
        for (index in tokens.indices) {
            val known = entry?.sectionGenres?.getOrNull(index)
            val matched = genres.filterTo(LinkedHashSet()) { key ->
                // Canonical genres are pre-computed; others match by their words, so the
                // discovered "superhero" also finds "Superhero & Villains".
                if (known != null && GenreMatcher.isCanonical(key)) key in known
                else GenreMatcher.matchesTokens(tokens[index], key)
            }
            if (matched.isNotEmpty()) result[index] = matched
        }
        return result
    }

    companion object {
        const val MIN_SOURCES_FOR_DISCOVERED = 2

        private fun signature(source: BrowseSource): List<String> =
            listOf(source.hasMainPage.toString()) + source.sectionNames

        val EMPTY = GenreIndex(emptyMap(), emptyList())

        fun build(sources: List<BrowseSource>): GenreIndex {
            val bySource = LinkedHashMap<String, SourceEntry>()
            val canonicalCounts = HashMap<String, Int>()
            val discoveredCounts = HashMap<String, Int>()
            for (source in sources) {
                val tokens = source.sectionNames.map { GenreMatcher.tokens(it) }
                val genres = tokens.map { sectionTokens ->
                    val keys = LinkedHashSet(GenreMatcher.canonicalGenresOfTokens(sectionTokens))
                    GenreMatcher.discoveredKeyOf(sectionTokens)?.let { keys += it }
                    keys
                }
                bySource[source.name] = SourceEntry(signature(source), tokens, genres)
                if (!source.hasMainPage) continue
                for (key in genres.flatMapTo(HashSet()) { it }) {
                    val counts = if (GenreMatcher.isCanonical(key)) canonicalCounts else discoveredCounts
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
            val canonicalOrder = GenreMatcher.CANONICAL.map { it.key }
            val available = canonicalCounts.entries.map { it.key to it.value } +
                    discoveredCounts.entries.filter { it.value >= MIN_SOURCES_FOR_DISCOVERED }
                        .map { it.key to it.value }
            val sorted = available.sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenBy { canonicalOrder.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it.first }
            )
            return GenreIndex(bySource, sorted)
        }
    }
}
