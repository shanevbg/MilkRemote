package com.sheinsez.mdropdx12.remote.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class SavedServer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val host: String,
    val port: Int = 9270,
    val pin: String = "",
    val lastConnected: Long = 0,
)
