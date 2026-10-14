package com.sheinsez.mdropdx12.remote.data.db

import androidx.room.*
import com.sheinsez.mdropdx12.remote.data.model.SavedServer
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY lastConnected DESC")
    fun getAll(): Flow<List<SavedServer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: SavedServer): Long

    @Delete
    suspend fun delete(server: SavedServer)

    @Query("UPDATE servers SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Int, timestamp: Long)
}
