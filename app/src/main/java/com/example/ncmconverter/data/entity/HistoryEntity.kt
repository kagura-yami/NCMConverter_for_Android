package com.example.ncmconverter.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.ncmconverter.domain.model.HistoryStatus

/**
 * 历史记录实体
 *
 * 存储单次转换的结果日志
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** 原文件名 (xxx.ncm) */
    val sourceName: String,
    
    /** 输出文件名 (xxx.flac)，失败/跳过时为 null */
    val outputName: String?,
    
    /** 输出路径 (Music/xxx.flac)，失败/跳过时为 null */
    val outputPath: String?,
    
    /** 结果状态 */
    val status: HistoryStatus,
    
    /** 用户可读的结果描述 */
    val message: String,
    
    /** 使用的冲突策略 (SKIP/OVERWRITE/RENAME) */
    val conflictPolicy: String,
    
    /** 时间戳 (毫秒) */
    val timestamp: Long
)
