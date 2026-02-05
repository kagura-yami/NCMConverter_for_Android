package com.example.ncmconverter.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.example.ncmconverter.data.dao.HistoryDao
import com.example.ncmconverter.data.entity.HistoryEntity

/**
 * 应用数据库
 */
@Database(
    entities = [HistoryEntity::class],
    version = 1,
    exportSchema = false
)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun historyDao(): HistoryDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ncm_converter.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
