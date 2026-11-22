package com.rpgmaps.tabletop.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A folder in the map library. Folders nest: [parentId] is the folder this
 * one sits in, or null for the top level of the library. Maps point at their
 * folder through [MapEntity.folderId].
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
