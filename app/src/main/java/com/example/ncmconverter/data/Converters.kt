package com.example.ncmconverter.data

import androidx.room.TypeConverter
import com.example.ncmconverter.domain.model.HistoryStatus

/**
 * Room 类型转换器
 */
class Converters {
    
    @TypeConverter
    fun fromHistoryStatus(status: HistoryStatus): String {
        return status.name
    }
    
    @TypeConverter
    fun toHistoryStatus(value: String): HistoryStatus {
        return HistoryStatus.valueOf(value)
    }
}
