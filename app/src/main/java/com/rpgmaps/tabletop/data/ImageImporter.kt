package com.rpgmaps.tabletop.data

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Turns a picked image URI into the three files a map needs on disk.
 *
 * Battle maps are routinely 6000-10000 px on the long edge, which is far more
 * than either the tablet or a 1080p TV can show and far more than a Chromecast
 * can hold in memory. Import therefore produces a **display render** capped at
 * [DEFAULT_DISPLAY_MAX_DIM], and that render -- not the original -- is the
 * coordinate space the rest of the app works in. The original is kept so the
 * render can be regenerated at a different size later without a re-import.
 */
object ImageImporter {

    /**
     * Longest edge of the display render. 2048 is a deliberate compromise: it
     * is sharp on a 1080p TV even zoomed in ~2x, survives a legacy Chromecast's
     * memory budget, and keeps the JPEG small enough to push over Cast's
     * message channel in a few seconds.
     */
    const val DEFAULT_DISPLAY_MAX_DIM = 2048
    const val THUMB_MAX_DIM = 512

    data class Result(
        val id: String,
        val name: String,
        val imageW: Int,
        val imageH: Int,
        val fogW: Int,
        val fogH: Int,
    )

    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun import(
        context: Context,
        uri: Uri,
        displayMaxDim: Int = DEFAULT_DISPLAY_MAX_DIM,
    ): Result = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val files = MapFiles(context, id)
        files.ensureDir()

        try {
            copySource(context, uri, files.source)

            val bounds = readBounds(files.source)
                ?: throw ImportException("That file does not look like an image Android can read.")

            val display = decodeScaled(files.source, bounds, displayMaxDim)
                ?: throw ImportException("Ran out of memory decoding the image. Try a smaller file.")

            val imageW: Int
            val imageH: Int
            try {
                writeJpeg(display, files.display, quality = 88)
                imageW = display.width
                imageH = display.height

                val thumb = scaleTo(display, THUMB_MAX_DIM)
                try {
                    writeJpeg(thumb, files.thumb, quality = 80)
                } finally {
                    if (thumb !== display) thumb.recycle()
                }
            } finally {
                display.recycle()
            }

            val (fogW, fogH) = com.rpgmaps.tabletop.fog.FogMask.sizeFor(imageW, imageH)

            Result(
                id = id,
                name = displayName(context, uri),
                imageW = imageW,
                imageH = imageH,
                fogW = fogW,
                fogH = fogH,
            )
        } catch (t: Throwable) {
            // Never leave a half-imported map behind for the library to show.
            files.deleteAll()
            throw t
        }
    }

    // --- steps ----------------------------------------------------------

    private fun copySource(context: Context, uri: Uri, dest: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Could not open the selected file.")
        input.use { src ->
            FileOutputStream(dest).use { out -> src.copyTo(out, DEFAULT_BUFFER_SIZE) }
        }
    }

    private fun readBounds(file: File): BitmapFactory.Options? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts else null
    }

    /**
     * Decodes [file] down to at most [maxDim] on its long edge, applying EXIF
     * orientation. Subsampling happens during decode so a 10000 px source never
     * has to exist in memory at full size; the exact final size is reached with
     * a filtered matrix pass afterwards.
     */
    private fun decodeScaled(file: File, bounds: BitmapFactory.Options, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = try {
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        val matrix = Matrix()
        val swapsAxes = applyExifOrientation(file, matrix)

        // Dimensions after rotation, which is what the size cap applies to.
        val rotatedW = if (swapsAxes) decoded.height else decoded.width
        val rotatedH = if (swapsAxes) decoded.width else decoded.height

        val scale = (maxDim.toFloat() / maxOf(rotatedW, rotatedH)).coerceAtMost(1f)
        if (scale < 1f) matrix.postScale(scale, scale)

        if (matrix.isIdentity) return decoded

        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        } catch (e: OutOfMemoryError) {
            decoded.recycle()
            null
        }
    }

    /** Largest power-of-two subsample that still leaves at least [maxDim]. */
    internal fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    /**
     * Adds the EXIF orientation transform to [matrix]. Returns true when the
     * transform swaps the width and height axes, which the caller needs in
     * order to apply the size cap to the *displayed* dimensions.
     */
    private fun applyExifOrientation(file: File, matrix: Matrix): Boolean {
        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> { matrix.postRotate(90f); true }
            ExifInterface.ORIENTATION_ROTATE_180 -> { matrix.postRotate(180f); false }
            ExifInterface.ORIENTATION_ROTATE_270 -> { matrix.postRotate(270f); true }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> { matrix.postScale(-1f, 1f); false }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.postScale(1f, -1f); false }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f); true
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f); true
            }
            else -> false
        }
    }

    private fun scaleTo(source: Bitmap, maxDim: Int): Bitmap {
        val scale = (maxDim.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
        if (scale >= 1f) return source
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun writeJpeg(bitmap: Bitmap, dest: File, quality: Int) {
        FileOutputStream(dest).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw ImportException("Could not write ${dest.name}.")
            }
        }
    }

    /** A sensible default map name: the file name without its extension. */
    private fun displayName(context: Context, uri: Uri): String {
        val raw = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "Map"
        return raw.substringBeforeLast('.', raw)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifEmpty { "Map" }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}
