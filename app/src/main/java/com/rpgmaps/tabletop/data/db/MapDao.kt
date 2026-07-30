package com.rpgmaps.tabletop.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MapDao {

    /** Most recently played first, then newest imports. */
    @Query("SELECT * FROM maps ORDER BY lastOpenedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<MapEntity>>

    @Query("SELECT * FROM maps WHERE id = :id")
    suspend fun findById(id: String): MapEntity?

    @Query("SELECT name FROM maps")
    suspend fun allNames(): List<String>

    @Query("SELECT * FROM maps WHERE id = :id")
    fun observeById(id: String): Flow<MapEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(map: MapEntity)

    @Update
    suspend fun update(map: MapEntity)

    @Query("UPDATE maps SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE maps SET lastOpenedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("DELETE FROM maps WHERE id = :id")
    suspend fun delete(id: String)
}
