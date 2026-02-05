package com.example.ncmconverter.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.ncmconverter.data.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 历史记录 DAO
 */
@Dao
interface HistoryDao {
    
    /**
     * 插入一条历史记录
     */
    @Insert
    suspend fun insert(history: HistoryEntity): Long
    
    /**
     * 获取所有历史记录（按时间倒序）
     */
    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<HistoryEntity>>
    
    /**
     * 获取所有历史记录（一次性）
     */
    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    suspend fun getAll(): List<HistoryEntity>
    
    /**
     * 获取单条历史记录
     */
    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntity?
    
    /**
     * 获取记录总数
     */
    @Query("SELECT COUNT(*) FROM history")
    suspend fun getCount(): Int
    
    /**
     * 删除最旧的 N 条记录
     */
    @Query("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
    
    /**
     * 清空所有历史
     */
    @Query("DELETE FROM history")
    suspend fun deleteAll()
}
