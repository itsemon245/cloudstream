package com.lagradost.cloudstream3.ui.poster

/**
 * How a provider image is shown in a portrait (2:3) poster slot. Pure, so it is unit tested.
 *
 * `SearchResponse` has a single `posterUrl` and some providers put a landscape cover/backdrop
 * there; a centre-crop of such an image shows a sliver of the middle (cut-off titles). With
 * [Mode.FitWhole] wide images are shown whole over a blurred fill of themselves.
 *
 * [Mode] mirrors the `PosterFitMode` setting in `shared` (kept separate so this stays testable on
 * the plain JVM).
 *
 * The decision depends **only** on the image's size and the setting — never on where the image
 * came from (network, disk or memory cache) or on whether its pixels can be read (hardware
 * bitmaps): those only change the decoration ([Fill]), not the [Scale].
 */
object PosterFit {
    /** Width / height above which an image is not a poster. A 2:3 poster is 0.67. */
    const val WIDE_RATIO = 0.85f

    enum class Mode { FitWhole, FillPoster }
    enum class Scale { Crop, Fit }
    enum class Fill { None, Blur, Solid }

    data class Decision(val scale: Scale, val fill: Fill)

    fun isWide(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toFloat() / height > WIDE_RATIO

    /**
     * @param width/height the image's intrinsic size, or <= 0 when unknown (placeholders)
     * @param pixelsReadable false for hardware bitmaps or non-bitmap drawables
     */
    fun decide(width: Int, height: Int, mode: Mode, pixelsReadable: Boolean): Decision =
        if (mode == Mode.FitWhole && isWide(width, height)) {
            Decision(Scale.Fit, if (pixelsReadable) Fill.Blur else Fill.Solid)
        } else {
            Decision(Scale.Crop, Fill.None)
        }

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
