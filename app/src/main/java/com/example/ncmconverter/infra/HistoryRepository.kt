package com.example.ncmconverter.infra

import android.content.Context
import com.example.ncmconverter.data.AppDatabase
import com.example.ncmconverter.data.entity.HistoryEntity
import com.example.ncmconverter.domain.model.HistoryStatus
import kotlinx.coroutines.flow.Flow

/**
 * 历史记录仓库
 *
 * 管理转换历史的持久化存储
 */
class HistoryRepository(context: Context) {
    
    private val historyDao = AppDatabase.getInstance(context).historyDao()
    
    companion object {
        /** 最大保留记录数 */
        const val MAX_HISTORY_COUNT = 200
    }
    
    /**
     * 获取所有历史记录（按时间倒序）
     */
    val historyFlow: Flow<List<HistoryEntity>> = historyDao.getAllFlow()
    
    /**
     * 添加历史记录
     *
     * 自动清理超出 MAX_HISTORY_COUNT 的旧记录
     */
    suspend fun addHistory(
        sourceName: String,
        outputName: String?,
        outputPath: String?,
        status: HistoryStatus,
        message: String,
        conflictPolicy: String
    ) {
        val entity = HistoryEntity(
            sourceName = sourceName,
            outputName = outputName,
            outputPath = outputPath,
            status = status,
            message = message,
            conflictPolicy = conflictPolicy,
            timestamp = System.currentTimeMillis()
        )
        
        historyDao.insert(entity)
        
        // 清理超出的旧记录
        val count = historyDao.getCount()
        if (count > MAX_HISTORY_COUNT) {
            historyDao.deleteOldest(count - MAX_HISTORY_COUNT)
        }
    }
    
    /**
     * 获取单条历史记录
     */
    suspend fun getById(id: Long): HistoryEntity? {
        return historyDao.getById(id)
    }
    
    /**
     * 清空所有历史
     */
    suspend fun clearAll() {
        historyDao.deleteAll()
    }
}
