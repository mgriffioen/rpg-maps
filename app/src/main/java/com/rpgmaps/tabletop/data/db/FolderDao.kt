package com.rpgmaps.tabletop.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun findById(id: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE folders SET parentId = :parentId WHERE id = :id")
    suspend fun move(id: String, parentId: String?)

    /** Moves every folder directly inside [fromId] into [toId]. */
    @Query("UPDATE folders SET parentId = :toId WHERE parentId = :fromId")
    suspend fun reparentChildren(fromId: String, toId: String?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}
