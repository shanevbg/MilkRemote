package com.sheinsez.mdropdx12.remote.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sheinsez.mdropdx12.remote.data.model.ButtonConfig
import com.sheinsez.mdropdx12.remote.data.model.SavedServer

@Database(entities = [ButtonConfig::class, SavedServer::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun buttonDao(): ButtonDao
    abstract fun serverDao(): ServerDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mdx12_remote.db"
                ).build().also { instance = it }
            }
    }
}
