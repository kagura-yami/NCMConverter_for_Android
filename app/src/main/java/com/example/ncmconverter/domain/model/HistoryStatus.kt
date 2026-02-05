package com.example.ncmconverter.domain.model

/**
 * 历史记录状态
 *
 * 表示单次转换的结果状态
 */
enum class HistoryStatus {
    /** ✓ 转换完成 */
    SUCCESS,
    
    /** ⚠ 转换完成（有警告，如 FLAC 封面跳过） */
    SUCCESS_WITH_WARNING,
    
    /** ⏭ 文件已存在，已跳过 */
    SKIPPED,
    
    /** ✗ 转换失败 */
    FAILED,
    
    /** ⚠ 无法覆盖（权限限制），已保存为新文件 */
    OVERWRITE_FALLBACK
}
