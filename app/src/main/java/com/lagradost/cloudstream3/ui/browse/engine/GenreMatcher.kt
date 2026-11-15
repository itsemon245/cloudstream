package com.lagradost.cloudstream3.ui.browse.engine

import java.text.Normalizer

/**
 * Matches genres against the free-text section names providers expose on their home page
 * (`MainAPI.mainPage`). Pure and side-effect free, so it can be unit tested.
 *
 * Matching is done on whole word sequences, never on substrings, so "Action" matches
 * "Action Movies" and "Top Action" but not "Transaction"; known false friends such as
 * "Live Action" are excluded explicitly.
 */
object GenreMatcher {
    data class Genre(
        /** Stable id, used in queries, caches and preferences. */
        val key: String,
        /** English display name. */
        val name: String,
        /** Normalised word sequences that mean this genre. */
        val aliases: List<String>,
        /** Normalised word sequences that contain an alias but do not mean this genre. */
        val excludes: List<String> = emptyList(),
    )

    val CANONICAL: List<Genre> = listOf(
        Genre("action", "Action", listOf("action", "accion", "acao"), listOf("live action")),
        Genre("adventure", "Adventure", listOf("adventure", "adventures", "aventura")),
        Genre("animation", "Animation", listOf("animation", "animated", "animacion")),
        Genre("comedy", "Comedy", listOf("comedy", "comedies", "comedia", "sitcom", "sitcoms", "romcom", "rom com")),
        Genre("crime", "Crime", listOf("crime", "crimen")),
        Genre("documentary", "Documentary", listOf("documentary", "documentaries", "documental", "docuseries")),
        Genre("drama", "Drama", listOf("drama", "dramas", "kdrama", "k drama", "cdrama", "jdrama")),
        Genre("family", "Family", listOf("family", "kids", "familia")),
        Genre("fantasy", "Fantasy", listOf("fantasy", "fantasia")),
        Genre("history", "History", listOf("history", "historical", "historia")),
        Genre("horror", "Horror", listOf("horror", "terror")),
        Genre("music", "Music", listOf("music", "musical", "musicals")),
        Genre("mystery", "Mystery", listOf("mystery", "mysteries", "misterio")),
        Genre("romance", "Romance", listOf("romance", "romantic", "romcom", "rom com", "romance tv")),
        Genre("scifi", "Sci-Fi", listOf("sci fi", "scifi", "science fiction", "ciencia ficcion", "sci fi fantasy")),
        Genre("thriller", "Thriller", listOf("thriller", "thrillers", "suspense", "suspenso")),
        Genre("war", "War", listOf("war", "war politics", "guerra"), listOf("star wars")),
        Genre("western", "Western", listOf("western", "westerns")),
        Genre("sport", "Sport", listOf("sport", "sports")),
        Genre("reality", "Reality", listOf("reality", "reality tv")),
        Genre("biography", "Biography", listOf("biography", "biopic", "biographical")),
        Genre("korean", "Korean", listOf("korean", "kdrama", "k drama")),
        Genre("bollywood", "Bollywood", listOf("bollywood", "hindi")),
    )

    private val byKey = CANONICAL.associateBy { it.key }

    /** Lowercase, strip accents, keep letters/digits only, single spaces. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        var lastSpace = true
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c.isLetterOrDigit() -> {
                    sb.append(c.lowercaseChar()); lastSpace = false
                }

                !lastSpace -> {
                    sb.append(' '); lastSpace = true
                }
            }
        }
        return sb.toString().trim()
    }

    private fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() }

    private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var ok = true
            for (i in needle.indices) {
                if (haystack[start + i] != needle[i]) {
                    ok = false; break
                }
            }
            if (ok) return true
        }
        return false
    }

    /** The genre for [key], or an ad-hoc genre whose only alias is the key itself. */
    fun genreFor(key: String): Genre = byKey[key] ?: Genre(key, key, listOf(key))

    /**
     * Resolves a user-facing label (e.g. a detail page tag such as "Action & Adventure" or
     * "Sci-Fi") to genre keys. Unknown genres become ad-hoc keys (their normalised text).
     */
    fun resolve(label: String): List<String> {
        val parts = label.split('&', '/', ',', '|', '+')
            .flatMap { it.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val result = LinkedHashSet<String>()
        for (part in parts) {
            val partTokens = tokens(part)
            if (partTokens.isEmpty()) continue
            val canonical = CANONICAL.firstOrNull { genre ->
                genre.aliases.any { alias -> tokens(alias) == partTokens }
            }
            result += canonical?.key ?: partTokens.joinToString(" ")
        }
        return result.toList()
    }

    /** Display name for a genre key. */
    fun displayName(key: String): String =
        byKey[key]?.name ?: key.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }

    /** Whether a provider section named [sectionName] lists titles of genre [key]. */
    fun matches(sectionName: String, key: String): Boolean {
        val genre = genreFor(key)
        val sectionTokens = tokens(sectionName)
        if (sectionTokens.isEmpty()) return false
        if (genre.excludes.any { containsSequence(sectionTokens, tokens(it)) }) {
            // Still a match if the alias also appears outside the excluded phrase,
            // e.g. "Live Action & Action" — rare enough to ignore.
            return false
        }
        return genre.aliases.any { containsSequence(sectionTokens, tokens(it)) }
    }

    /** All canonical genre keys a section name matches. */
    fun canonicalGenresOf(sectionName: String): Set<String> =
        CANONICAL.filter { matches(sectionName, it.key) }.mapTo(LinkedHashSet()) { it.key }
}
