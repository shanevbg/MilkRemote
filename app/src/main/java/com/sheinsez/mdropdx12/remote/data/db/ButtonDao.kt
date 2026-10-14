package com.sheinsez.mdropdx12.remote.data.db

import androidx.room.*
import com.sheinsez.mdropdx12.remote.data.model.ButtonConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface ButtonDao {
    @Query("SELECT * FROM buttons ORDER BY position ASC")
    fun getAll(): Flow<List<ButtonConfig>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(button: ButtonConfig): Long

    @Delete
    suspend fun delete(button: ButtonConfig)

    @Query("UPDATE buttons SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: Int)

    @Query("SELECT * FROM buttons ORDER BY usageCount DESC LIMIT :limit")
    suspend fun getMostUsed(limit: Int = 10): List<ButtonConfig>
}
