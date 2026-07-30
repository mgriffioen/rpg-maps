package com.rpgmaps.tabletop.fog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.rpgmaps.tabletop.display.protocol.FogOp
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * The fog layer for one map.
 *
 * Stored as an ARGB_8888 bitmap where **opaque black means hidden** and
 * **transparent means revealed**. Keeping it as a real bitmap (rather than
 * replaying a stroke list) means drawing cost does not grow over a session, and
 * PNG-compressing a mostly-black two-tone image is cheap enough to snapshot on
 * every stroke for undo.
 *
 * The mask is deliberately much smaller than the map itself -- see
 * [FOG_MAX_DIM]. Fog edges are soft, so extra resolution buys nothing visible
 * while costing memory on the tablet and transfer time to the Chromecast.
 */
class FogMask private constructor(val bitmap: Bitmap) {

    private val canvas = Canvas(bitmap)

    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    /** Applies one brush stroke in place. Coordinates are mask pixels. */
    fun apply(op: FogOp) {
        if (op.pts.size < 2) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = op.radius * 2f
            color = Color.BLACK
            if (op.softness > 0f) {
                // Blur is only legal on a software canvas, which is what a
                // Canvas wrapping a Bitmap always is.
                val blur = (op.radius * op.softness).coerceAtLeast(0.6f)
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            // Revealing punches a hole; hiding paints black the normal way.
            if (op.reveal) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        if (op.pts.size == 2) {
            canvas.drawPoint(op.pts[0], op.pts[1], paint)
            return
        }

        val path = Path().apply {
            moveTo(op.pts[0], op.pts[1])
            var i = 2
            while (i + 1 < op.pts.size) {
                lineTo(op.pts[i], op.pts[i + 1])
                i += 2
            }
        }
        canvas.drawPath(path, paint)
    }

    /** Hides or reveals the entire map at once. */
    fun fill(fogged: Boolean) {
        canvas.drawColor(
            if (fogged) Color.BLACK else Color.TRANSPARENT,
            PorterDuff.Mode.SRC,
        )
    }

    /** PNG bytes of the mask -- used for undo snapshots, saving, and resync. */
    fun toPng(): ByteArray = ByteArrayOutputStream(64 * 1024).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    /** Overwrites this mask with [png], rescaling if the size differs. */
    fun restoreFrom(png: ByteArray) {
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                decoded,
                android.graphics.Rect(0, 0, decoded.width, decoded.height),
                android.graphics.Rect(0, 0, width, height),
                paint,
            )
        } finally {
            decoded.recycle()
        }
    }

    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        /**
         * Longest edge of the fog mask, in pixels. 1536 keeps a 4:3 mask under
         * ~7 MB in memory and its PNG under ~100 KB, which matters because the
         * Cast transport pushes that PNG through a 64 KB message channel.
         */
        const val FOG_MAX_DIM = 1536

        /**
         * Mask dimensions for a map of [imageW] x [imageH], preserving aspect.
         *
         * Rounds rather than truncates: `1536f / 8000f` is not exactly
         * representable, so truncation can turn an intended 1536 into 1535 and
         * skew the mask against the image by a fraction of a pixel.
         */
        fun sizeFor(imageW: Int, imageH: Int): Pair<Int, Int> {
            val longest = maxOf(imageW, imageH).coerceAtLeast(1)
            if (longest <= FOG_MAX_DIM) return imageW.coerceAtLeast(1) to imageH.coerceAtLeast(1)
            val scale = FOG_MAX_DIM.toDouble() / longest
            return (imageW * scale).roundToInt().coerceAtLeast(1) to
                (imageH * scale).roundToInt().coerceAtLeast(1)
        }

        /** A new, fully hidden mask. */
        fun createHidden(width: Int, height: Int): FogMask {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return FogMask(bmp).also { it.fill(fogged = true) }
        }

        /**
         * Loads a saved mask, falling back to a fully hidden one if the file is
         * missing or unreadable. A corrupt fog file should never stop a map
         * from opening mid-session.
         */
        fun fromPngOrHidden(png: ByteArray?, width: Int, height: Int): FogMask {
            val mask = createHidden(width, height)
            if (png != null && png.isNotEmpty()) mask.restoreFrom(png)
            return mask
        }
    }
}
