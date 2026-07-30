package com.rpgmaps.tabletop.data

import android.content.Context
import java.io.File

/**
 * On-disk layout for one map. Everything lives under the app's private files
 * directory, so importing a map copies it out of the picker's URI and the app
 * never needs storage permissions or a persistable URI grant.
 *
 * ```
 * files/maps/<id>/source     original bytes, exactly as imported
 * files/maps/<id>/display.jpg  downscaled render -- the coordinate space used
 *                              by viewports, grid and calibration
 * files/maps/<id>/thumb.jpg    library grid thumbnail
 * files/maps/<id>/fog.png      fog mask, black = hidden
 * ```
 */
class MapFiles(context: Context, val id: String) {

    val dir: File = File(File(context.filesDir, "maps"), id)

    val source: File get() = File(dir, "source")
    val display: File get() = File(dir, "display.jpg")
    val thumb: File get() = File(dir, "thumb.jpg")
    val fog: File get() = File(dir, "fog.png")

    fun ensureDir(): File = dir.apply { mkdirs() }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    companion object {
        fun rootDir(context: Context): File = File(context.filesDir, "maps")
    }
}
