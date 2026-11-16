package com.lagradost.cloudstream3.ui.browse.engine

/**
 * Decisions for showing provider images in the portrait (2:3) poster slot. `SearchResponse` has a
 * single `posterUrl` and some providers put a landscape cover/backdrop there; a centre-crop of
 * such an image shows a sliver of the middle (cut-off titles). Wide images are therefore shown
 * whole, fitted, over a blurred fill of themselves.
 */
object PosterFit {
    /** Width / height above which an image is not a poster. A 2:3 poster is 0.67. */
    const val WIDE_RATIO = 0.85f

    fun isWide(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toFloat() / height > WIDE_RATIO

    /** Up to two initials for the placeholder ("The Night Manager" -> "NM", "1917" -> "19"). */
    fun initials(title: String): String {
        val words = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val meaningful = words.filter { it.lowercase() !in ARTICLES }.ifEmpty { words }
        return when {
            meaningful.isEmpty() -> "?"
            meaningful.size == 1 -> meaningful[0].take(2).uppercase()
            else -> (meaningful[0].take(1) + meaningful[1].take(1)).uppercase()
        }
    }

    private val ARTICLES = setOf("the", "a", "an")
}
