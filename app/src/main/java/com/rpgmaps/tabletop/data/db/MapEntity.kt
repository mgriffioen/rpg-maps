package com.rpgmaps.tabletop.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One map in the library. The heavy data -- source image, display render,
 * thumbnail, fog mask -- lives on disk under `files/maps/<id>/`; see
 * [com.rpgmaps.tabletop.data.MapFiles]. Only the metadata is in the database.
 */
@Entity(tableName = "maps")
data class MapEntity(
    @PrimaryKey val id: String,
    val name: String,

    /** Dimensions of the *display render*, which is the coordinate space that
     *  viewports, grids and calibration are all expressed in. */
    val imageW: Int,
    val imageH: Int,

    val fogW: Int,
    val fogH: Int,

    /**
     * Display-image pixels per 5 ft battle square. 0 means "not calibrated
     * yet", in which case scale-to-life mode is unavailable for this map.
     */
    @ColumnInfo(defaultValue = "0") val pxPerSquare: Float = 0f,
    @ColumnInfo(defaultValue = "0") val gridOffsetX: Float = 0f,
    @ColumnInfo(defaultValue = "0") val gridOffsetY: Float = 0f,
    @ColumnInfo(defaultValue = "0") val gridEnabled: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0L,
)
