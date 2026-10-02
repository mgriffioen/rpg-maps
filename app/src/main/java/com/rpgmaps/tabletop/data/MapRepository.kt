package com.rpgmaps.tabletop.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.rpgmaps.tabletop.data.db.AppDatabase
import com.rpgmaps.tabletop.data.db.MapDao
import com.rpgmaps.tabletop.data.db.MapEntity
import com.rpgmaps.tabletop.display.protocol.DisplayJson
import com.rpgmaps.tabletop.display.protocol.Drawing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The library: map metadata in Room, map pixels on disk.
 *
 * Everything that touches disk is [Dispatchers.IO] here so callers -- view
 * models, the HTTP server -- never have to think about it.
 */
class MapRepository(private val context: Context) {

    private val dao: MapDao = AppDatabase.get(context).mapDao()

    fun observeMaps(): Flow<List<MapEntity>> = dao.observeAll()

    fun observeMap(id: String): Flow<MapEntity?> = dao.observeById(id)

    suspend fun getMap(id: String): MapEntity? = dao.findById(id)

    fun files(id: String): MapFiles = MapFiles(context, id)

    // --- library management ---------------------------------------------

    /** Imports a picked image and returns the stored map. */
    suspend fun importMap(uri: Uri, displayMaxDim: Int = ImageImporter.DEFAULT_DISPLAY_MAX_DIM): MapEntity {
        val result = ImageImporter.import(context, uri, displayMaxDim)
        val entity = MapEntity(
            id = result.id,
            name = uniqueName(result.name),
            imageW = result.imageW,
            imageH = result.imageH,
            fogW = result.fogW,
            fogH = result.fogH,
        )
        dao.insert(entity)
        return entity
    }

    /**
     * Puts a new image into an existing map, keeping its name, grid
     * calibration, fog and drawings. For a map whose files were lost -- an
     * uninstall restores the library list from Android's backup but not the
     * images, which are too big to back up -- this is what brings it back
     * without recalibrating.
     *
     * The image goes through the normal import into a scratch map folder and
     * its files are then moved across, so a failed import leaves the map
     * exactly as it was.
     *
     * Everything stored in map pixels is scaled if the new render is a
     * different size: the same file picked again renders identically and
     * nothing moves, but a higher-resolution copy of the same artwork keeps
     * the grid on its squares.
     */
    suspend fun replaceImage(
        id: String,
        uri: Uri,
        displayMaxDim: Int = ImageImporter.DEFAULT_DISPLAY_MAX_DIM,
    ): MapEntity {
        val map = dao.findById(id) ?: throw ImageImporter.ImportException("That map is no longer in the library.")
        val result = ImageImporter.import(context, uri, displayMaxDim)

        withContext(Dispatchers.IO) {
            val scratch = MapFiles(context, result.id)
            val target = MapFiles(context, id)
            try {
                target.ensureDir()
                for (name in listOf(scratch.source, scratch.display, scratch.thumb)) {
                    val dest = File(target.dir, name.name)
                    if (!name.renameTo(dest)) {
                        name.copyTo(dest, overwrite = true)
                    }
                }
            } finally {
                scratch.deleteAll()
            }
        }

        val scale = if (map.imageW > 0) result.imageW.toFloat() / map.imageW else 1f
        if (scale != 1f) {
            val drawings = readDrawings(id)
            if (drawings.isNotEmpty()) {
                writeDrawings(
                    id,
                    drawings.map { d -> d.copy(width = d.width * scale, pts = d.pts.map { it * scale }) },
                )
            }
        }

        // The fog mask is kept as it is: it is stored at its own resolution
        // and FogMask.restoreFrom stretches it onto the new fog size.
        val updated = map.copy(
            imageW = result.imageW,
            imageH = result.imageH,
            fogW = result.fogW,
            fogH = result.fogH,
            pxPerSquare = map.pxPerSquare * scale,
            gridOffsetX = map.gridOffsetX * scale,
            gridOffsetY = map.gridOffsetY * scale,
        )
        dao.update(updated)
        return updated
    }

    /** Ids of maps whose display image is gone from disk. */
    suspend fun missingImages(ids: List<String>): Set<String> = withContext(Dispatchers.IO) {
        ids.filterTo(HashSet()) { !MapFiles(context, it).display.exists() }
    }

    /** Appends " 2", " 3", ... when a map of the same name already exists. */
    private suspend fun uniqueName(base: String): String {
        val existing = dao.allNames().toSet()
        if (base !in existing) return base
        var n = 2
        while ("$base $n" in existing) n++
        return "$base $n"
    }

    suspend fun rename(id: String, name: String) {
        dao.rename(id, name.trim().ifEmpty { "Map" })
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        MapFiles(context, id).deleteAll()
    }

    suspend fun markOpened(id: String) = dao.touch(id, System.currentTimeMillis())

    suspend fun updateMap(map: MapEntity) = dao.update(map)

    // --- pixels ----------------------------------------------------------

    /** The downscaled render used for both the DM view and the player view. */
    suspend fun loadDisplayBitmap(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = MapFiles(context, id).display
        if (!file.exists()) return@withContext null
        try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Raw JPEG bytes of the display render, for streaming to a receiver. */
    suspend fun readDisplayBytes(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = MapFiles(context, id).display
        if (file.exists()) file.readBytes() else null
    }

    suspend fun readFogPng(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = MapFiles(context, id).fog
        if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
    }

    /**
     * Saves the fog mask. Written to a temp file and renamed so a crash or a
     * dead battery mid-save cannot leave a truncated mask that would come back
     * as a blank map next session.
     */
    suspend fun writeFogPng(id: String, png: ByteArray) = withContext(Dispatchers.IO) {
        val files = MapFiles(context, id)
        files.ensureDir()
        writeAtomically(files.fog, png)
    }

    /**
     * The map's drawings, or none if the file is missing or unreadable. Like a
     * corrupt fog mask, a corrupt drawings file must not stop a map opening.
     */
    suspend fun readDrawings(id: String): List<Drawing> = withContext(Dispatchers.IO) {
        val file = MapFiles(context, id).drawings
        if (!file.exists()) return@withContext emptyList()
        runCatching { DisplayJson.decodeFromString(drawingsSerializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    suspend fun writeDrawings(id: String, drawings: List<Drawing>) = withContext(Dispatchers.IO) {
        val files = MapFiles(context, id)
        files.ensureDir()
        writeAtomically(files.drawings, DisplayJson.encodeToString(drawingsSerializer, drawings).toByteArray())
    }

    /** Temp file and rename, so a dead battery mid-save cannot truncate [target]. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    private val drawingsSerializer = ListSerializer(Drawing.serializer())
}
