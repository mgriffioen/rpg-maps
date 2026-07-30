package com.rpgmaps.tabletop.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.rpgmaps.tabletop.data.db.AppDatabase
import com.rpgmaps.tabletop.data.db.MapDao
import com.rpgmaps.tabletop.data.db.MapEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
        val tmp = File(files.dir, "fog.png.tmp")
        tmp.writeBytes(png)
        if (!tmp.renameTo(files.fog)) {
            files.fog.writeBytes(png)
            tmp.delete()
        }
    }
}
