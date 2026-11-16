package com.lagradost.cloudstream3.ui.poster

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.preference.PreferenceManager
import coil3.asImage
import coil3.dispose
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import coil3.target.ImageViewTarget
import coil3.transition.CrossfadeDrawable
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.POSTER_FIT_MODE_KEY
import com.lagradost.cloudstream4.PosterFitMode

/**
 * The one place that puts a `SearchResponse` poster into a portrait poster slot (Search, quick
 * search, Home rows, Library, Browse, recommendations — everything bound by
 * `SearchResultBuilder`), so the "landscape image" setting applies everywhere the same way.
 *
 * Why a custom target: the fit/crop decision is made in [PosterTarget.drawable]'s setter, i.e.
 * on every drawable that reaches the view — placeholder, result, error, memory-cache hit or not,
 * fresh or recycled view. Earlier the scale was switched in a success listener, which lost
 * against Coil's crossfade: a crossfaded result draws with the scale captured when the request
 * was built (centre-crop), so disk/network results came out cropped while memory-cache hits
 * (never crossfaded) came out fitted, and the same tile changed with the cache state. With
 * "Fit whole image" posters therefore don't crossfade.
 */
object PosterLoader {
    @Volatile
    private var mode: PosterFit.Mode? = null

    // Kept strongly: SharedPreferences only holds listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == POSTER_FIT_MODE_KEY) mode = null
    }
    @Volatile
    private var listening = false

    /** The current setting; re-read after it changes, so a change applies on the next bind. */
    fun mode(context: Context): PosterFit.Mode {
        mode?.let { return it }
        if (!listening) {
            listening = true
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .registerOnSharedPreferenceChangeListener(listener)
        }
        val setting = AppSettings(context.applicationContext).ui.posterFitMode.get()
        return when (setting) {
            PosterFitMode.FitWhole -> PosterFit.Mode.FitWhole
            PosterFitMode.FillPoster -> PosterFit.Mode.FillPoster
        }.also { mode = it }
    }

    fun load(view: ImageView, card: SearchResponse) {
        val mode = mode(view.context)
        val placeholder = InitialsDrawable(view.context, PosterFit.initials(card.name))
        val url = card.posterUrl
        if (url.isNullOrBlank()) {
            view.dispose()
            apply(view, placeholder, mode)
            view.setImageDrawable(placeholder)
            return
        }
        view.loadImage(url, card.posterHeaders) {
            target(PosterTarget(view, mode))
            placeholder(placeholder.asImage())
            error(placeholder.asImage())
            // A crossfade keeps the request's crop scale; only fade when the result is cropped anyway.
            if (mode == PosterFit.Mode.FitWhole) crossfade(false)
        }
    }

    /** Plain centre-crop with no fill, as the XML layouts declare. */
    fun resetToCrop(view: ImageView) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.background = null
    }

    /** Sets scale type and background for [drawable]; resets both for anything not wide. */
    internal fun apply(view: ImageView, drawable: Drawable?, mode: PosterFit.Mode) {
        val image = (drawable as? CrossfadeDrawable)?.end ?: drawable
        val bitmap = (image as? BitmapDrawable)?.bitmap
        val readable = bitmap != null && !bitmap.isRecycled &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || bitmap.config != Bitmap.Config.HARDWARE)
        val decision = PosterFit.decide(
            image?.intrinsicWidth ?: -1, image?.intrinsicHeight ?: -1, mode, readable
        )
        when (decision.scale) {
            PosterFit.Scale.Crop -> {
                view.scaleType = ImageView.ScaleType.CENTER_CROP
                view.background = null
            }

            PosterFit.Scale.Fit -> {
                view.scaleType = ImageView.ScaleType.FIT_CENTER
                view.background = fill(view, bitmap.takeIf { decision.fill == PosterFit.Fill.Blur })
            }
        }
    }

    /**
     * The image scaled to 6×9 px and drawn back up with filtering (a blur without RenderEffect),
     * darkened; a solid dark fill when the pixels can't be read. Decoration only.
     */
    private fun fill(view: ImageView, bitmap: Bitmap?): Drawable {
        val dim = 0x99000000.toInt().toDrawable()
        if (bitmap == null) {
            return LayerDrawable(arrayOf(view.context.colorFromAttribute(R.attr.primaryGrayBackground).toDrawable(), dim))
        }
        return try {
            val tiny = bitmap.scale(6, 9, filter = true).toDrawable(view.resources).apply { isFilterBitmap = true }
            LayerDrawable(arrayOf(tiny, dim))
        } catch (t: Throwable) {
            logError(t)
            LayerDrawable(arrayOf(view.context.colorFromAttribute(R.attr.primaryGrayBackground).toDrawable(), dim))
        }
    }

    private class PosterTarget(view: ImageView, private val mode: PosterFit.Mode) : ImageViewTarget(view) {
        override var drawable: Drawable?
            get() = super.drawable
            set(value) {
                apply(view, value, mode)
                super.drawable = value
            }
    }

    /** Title initials centred on the card colour; no intrinsic size, so it fills the slot. */
    private class InitialsDrawable(context: Context, private val text: String) : Drawable() {
        private val background = context.colorFromAttribute(R.attr.primaryGrayBackground)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.colorFromAttribute(R.attr.grayTextColor)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawColor(background)
            paint.textSize = b.width() / 3.2f
            val y = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, b.exactCenterX(), y, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
