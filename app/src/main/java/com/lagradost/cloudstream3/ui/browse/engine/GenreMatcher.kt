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
    /** How a key is grouped in the UI. Selection works the same for every category. */
    enum class Category { Genre, Region, Platform }

    data class Genre(
        /** Stable id, used in queries, caches and preferences. */
        val key: String,
        /** English display name. */
        val name: String,
        /** Normalised word sequences that mean this genre. */
        val aliases: List<String>,
        /** Normalised word sequences that contain an alias but do not mean this genre. */
        val excludes: List<String> = emptyList(),
        val category: Category = Category.Genre,
    ) {
        internal val aliasTokens: List<List<String>> by lazy { aliases.map { tokens(it) } }
        internal val excludeTokens: List<List<String>> by lazy { excludes.map { tokens(it) } }
    }

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
        // Regions / film industries: shown in their own group.
        Genre("korean", "Korean", listOf("korean", "kdrama", "k drama"), category = Category.Region),
        Genre("bollywood", "Bollywood", listOf("bollywood", "hindi"), category = Category.Region),
        Genre("hollywood", "Hollywood", listOf("hollywood"), category = Category.Region),
        Genre("indian", "Indian", listOf("indian", "desi"), listOf("south indian"), category = Category.Region),
        Genre("south indian", "South Indian", listOf("south indian", "tollywood", "kollywood"), category = Category.Region),
        Genre("tamil", "Tamil", listOf("tamil"), category = Category.Region),
        Genre("telugu", "Telugu", listOf("telugu"), category = Category.Region),
        Genre("malayalam", "Malayalam", listOf("malayalam"), category = Category.Region),
        Genre("kannada", "Kannada", listOf("kannada"), category = Category.Region),
        Genre("bengali", "Bengali", listOf("bengali", "bangla"), category = Category.Region),
        Genre("punjabi", "Punjabi", listOf("punjabi"), category = Category.Region),
        Genre("pakistani", "Pakistani", listOf("pakistani"), category = Category.Region),
        Genre("chinese", "Chinese", listOf("chinese", "cdrama", "c drama"), category = Category.Region),
        Genre("japanese", "Japanese", listOf("japanese", "jdrama", "j drama"), category = Category.Region),
        Genre("thai", "Thai", listOf("thai"), category = Category.Region),
        Genre("turkish", "Turkish", listOf("turkish"), category = Category.Region),
        // Streaming platforms: aliases merge "Amazon", "Amazon Prime" and "Prime Video".
        Genre("netflix", "Netflix", listOf("netflix"), category = Category.Platform),
        Genre("prime video", "Prime Video", listOf("amazon", "prime video", "amazon prime"), category = Category.Platform),
        Genre("disney plus", "Disney+", listOf("disney", "disney plus"), category = Category.Platform),
        Genre("hotstar", "Hotstar", listOf("hotstar"), category = Category.Platform),
        Genre("apple tv", "Apple TV+", listOf("apple", "apple tv"), category = Category.Platform),
        Genre("hbo", "HBO", listOf("hbo", "hbo max"), category = Category.Platform),
        Genre("hulu", "Hulu", listOf("hulu"), category = Category.Platform),
        Genre("paramount", "Paramount+", listOf("paramount", "paramount plus"), category = Category.Platform),
        Genre("peacock", "Peacock", listOf("peacock"), category = Category.Platform),
        Genre("mx player", "MX Player", listOf("mx original", "mx originals", "mx player"), category = Category.Platform),
        Genre("zee5", "ZEE5", listOf("zee5", "zee 5"), category = Category.Platform),
        Genre("sonyliv", "SonyLIV", listOf("sonyliv", "sony liv"), category = Category.Platform),
        Genre("jiocinema", "JioCinema", listOf("jiocinema", "jio cinema"), category = Category.Platform),
        Genre("crunchyroll", "Crunchyroll", listOf("crunchyroll"), category = Category.Platform),
        Genre("aha", "aha", listOf("aha video", "aha originals"), category = Category.Platform),
        Genre("hoichoi", "hoichoi", listOf("hoichoi"), category = Category.Platform),
        Genre("altbalaji", "ALTBalaji", listOf("altbalaji", "alt balaji"), category = Category.Platform),
        Genre("voot", "Voot", listOf("voot"), category = Category.Platform),
        Genre("starz", "Starz", listOf("starz"), category = Category.Platform),
        Genre("showtime", "Showtime", listOf("showtime"), category = Category.Platform),
        Genre("amc", "AMC+", listOf("amc", "amc plus"), category = Category.Platform),
    )

    private val byKey = CANONICAL.associateBy { it.key }

    /** Words that describe a list rather than a genre; dropped when discovering genres. */
    private val STOP_WORDS = setOf(
        "latest", "trending", "popular", "new", "newest", "recent", "recently", "top", "best",
        "featured", "recommended", "updated", "update", "updates", "ongoing", "completed",
        "upcoming", "hot", "today", "now", "this", "week", "weekly", "month", "monthly", "year",
        "all", "more", "added", "release", "releases", "released", "airing", "episodes", "episode",
        "movies", "movie", "films", "film", "series", "tv", "show", "shows", "anime", "cartoons",
        "cartoon", "dubbed", "subbed", "dub", "sub", "hd", "4k", "full", "free", "watch", "online",
        "the", "of", "and", "in", "on", "for", "to", "a", "an", "de", "la", "el", "los", "las",
        "rated", "viewed", "most", "random", "home", "list", "collection", "collections",
        // Not genres: formats, languages, site jargon.
        "air", "aired", "web", "personal", "dual", "audio", "multi", "original", "originals",
        "exclusive", "exclusives", "special", "specials", "premium", "vip", "ott", "imdb",
        "english", "uhd", "hdr", "bluray", "webdl", "hevc", "x264", "x265", "esub",
        "esubs", "subs", "subtitles", "org", "dvd", "cam", "hq", "region", "country", "genre",
        "genres", "category", "categories", "others", "other", "misc", "ova", "ona",
        "print", "prints", "rip", "hdrip", "hdcam", "predvd", "camrip", "hdtc", "hdts",
    )

    private val RESOLUTION = Regex("\\d+p")

    /** Tokens like "1080p" or "2024" are never genre words. */
    private fun isJunkToken(token: String): Boolean =
        token.length <= 1 || token.none { it.isLetter() } || RESOLUTION.matches(token)

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

    internal fun tokens(text: String): List<String> =
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

    fun isCanonical(key: String): Boolean = key in byKey

    /** UI group of a key; discovered and ad-hoc keys are genres. */
    fun categoryOf(key: String): Category = byKey[key]?.category ?: Category.Genre

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
    fun matches(sectionName: String, key: String): Boolean =
        matchesTokens(tokens(sectionName), key)

    /** [matches] on an already tokenised section name (see [tokens]). */
    fun matchesTokens(sectionTokens: List<String>, key: String): Boolean {
        if (sectionTokens.isEmpty()) return false
        val genre = byKey[key]
        if (genre == null) {
            // Ad-hoc genre (e.g. a detail page tag or a discovered genre): its own words.
            return containsSequence(sectionTokens, key.split(' '))
        }
        if (genre.excludeTokens.any { containsSequence(sectionTokens, it) }) {
            // Still a match if the alias also appears outside the excluded phrase,
            // e.g. "Live Action & Action" — rare enough to ignore.
            return false
        }
        return genre.aliasTokens.any { containsSequence(sectionTokens, it) }
    }

    /** All canonical genre keys a section name matches. */
    fun canonicalGenresOf(sectionName: String): Set<String> = canonicalGenresOfTokens(tokens(sectionName))

    fun canonicalGenresOfTokens(sectionTokens: List<String>): Set<String> =
        CANONICAL.filter { matchesTokens(sectionTokens, it.key) }.mapTo(LinkedHashSet()) { it.key }

    /**
     * A genre-like key for a section that matches no canonical genre, e.g. "Superhero Movies" ->
     * "superhero", "Martial Arts" -> "martial arts". Null for list-style names such as
     * "Latest Movies" or "Trending Now", and for anything longer than three words.
     */
    fun discoveredKeyOf(sectionTokens: List<String>): String? {
        if (canonicalGenresOfTokens(sectionTokens).isNotEmpty()) return null
        val words = sectionTokens.filter { it !in STOP_WORDS && !isJunkToken(it) }
        if (words.isEmpty() || words.size > 3) return null
        return words.joinToString(" ")
    }
}
