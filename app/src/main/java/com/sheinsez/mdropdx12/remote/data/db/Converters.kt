package com.sheinsez.mdropdx12.remote.data.db

import androidx.room.TypeConverter
import com.sheinsez.mdropdx12.remote.data.model.ButtonActionType

class Converters {
    @TypeConverter
    fun fromActionType(value: ButtonActionType): String = value.name

    @TypeConverter
    fun toActionType(value: String): ButtonActionType = ButtonActionType.valueOf(value)
}
