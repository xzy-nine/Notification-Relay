package com.xzyht.notifyrelay.common.data.database.converter

import androidx.room.TypeConverter
import java.util.Date

/**
 * Room类型转换器
 * 用于处理Room不支持的类型转换
 */
class RoomTypeConverters {
    /**
     * Date转Long
     */
    @TypeConverter
    fun fromDate(date: Date): Long {
        return date.time
    }
    
    /**
     * Long转Date
     */
    @TypeConverter
    fun toDate(time: Long): Date {
        return Date(time)
    }
    
    /**
     * String转Boolean
     */
    @TypeConverter
    fun fromStringToBoolean(value: String?): Boolean {
        return value?.toBoolean() ?: false
    }
    
    /**
     * Boolean转String
     */
    @TypeConverter
    fun fromBooleanToString(value: Boolean): String {
        return value.toString()
    }
    
    /**
     * String转Int
     */
    @TypeConverter
    fun fromStringToInt(value: String?): Int {
        return value?.toIntOrNull() ?: 0
    }
    
    /**
     * Int转String
     */
    @TypeConverter
    fun fromIntToString(value: Int): String {
        return value.toString()
    }
    
    /**
     * String转Long
     */
    @TypeConverter
    fun fromStringToLong(value: String?): Long {
        return value?.toLongOrNull() ?: 0L
    }
    
    /**
     * Long转String
     */
    @TypeConverter
    fun fromLongToString(value: Long): String {
        return value.toString()
    }
}
