package com.lagradost.cloudstream3.ui.browse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import coil3.BitmapImage
import coil3.asImage
import coil3.dispose
import coil3.request.allowHardware
import coil3.request.error
import coil3.request.placeholder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.browse.engine.PosterFit
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute

/**
 * Poster loading for the Browse grid:
 * - a placeholder with the title's initials while loading, on error and without a url
 *   (instead of an empty dark tile);
 * - a landscape cover/backdrop in the portrait slot is shown whole (fit) over a blurred, darkened
 *   fill of itself instead of being centre-cropped. The fill is the image scaled down to 6×9
 *   pixels and drawn back up with filtering: a blur without RenderEffect, cheap on any API.
 * No extra requests: the decision uses the already loaded image.
 */
object BrowsePosters {
    fun load(view: ImageView, card: SearchResponse) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.background = null
        val placeholder = InitialsDrawable(view.context, PosterFit.initials(card.name))
        val url = card.posterUrl
        if (url.isNullOrBlank()) {
            view.dispose()
            view.setImageDrawable(placeholder)
            return
        }
        view.loadImage(url, card.posterHeaders) {
            placeholder(placeholder.asImage())
            error(placeholder.asImage())
            // Software bitmap so the fill can be scaled from it.
            allowHardware(false)
            listener(onSuccess = { _, result ->
                val image = result.image
                if (PosterFit.isWide(image.width, image.height)) {
                    (image as? BitmapImage)?.bitmap?.let { fitWithFill(view, it) }
                }
            })
        }
    }

    private fun fitWithFill(view: ImageView, bitmap: Bitmap) {
        try {
            val tiny = bitmap.scale(6, 9, filter = true)
            val fill = tiny.toDrawable(view.resources).apply { isFilterBitmap = true }
            view.background = LayerDrawable(arrayOf(fill, 0x99000000.toInt().toDrawable()))
            view.scaleType = ImageView.ScaleType.FIT_CENTER
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /** Title initials centred on the card colour. */
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
