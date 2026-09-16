package com.sheinsez.mdropdx12.remote.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ButtonActionType {
    Signal, SendKey, ScriptCommand, LoadPreset, Message, RunScript
}

@Entity(tableName = "buttons")
data class ButtonConfig(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val actionType: ButtonActionType,
    val payload: String,
    val icon: String = "",
    val position: Int = 0,
    val usageCount: Int = 0,
)
