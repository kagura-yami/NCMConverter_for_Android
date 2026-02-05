package com.example.ncmconverter.domain.model

/**
 * 用户设置
 *
 * @see §5 核心数据结构
 */
data class AppSettings(
    val conflictPolicy: ConflictPolicy,
    val autoDeleteSource: Boolean,
    val debugMode: Boolean = false  // 调试模式：显示更多技术信息
)

/**
 * 冲突策略
 *
 * @see §5 核心数据结构
 * @see §6.4 去重与冲突策略
 */
enum class ConflictPolicy {
    SKIP,       // 跳过
    OVERWRITE,  // 覆盖
    RENAME      // 重命名 (song(1).mp3)
}
