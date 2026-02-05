package com.example.ncmconverter.domain.model

/**
 * 扫描结果 - NCM 文件引用
 *
 * 【重要约束】documentId 与 URI 的关系：
 * - documentId 仅作为 DocumentsContract 操作参数使用
 * - 实际 URI 由 SAFManager 根据 treeUri + documentId 临时构造
 * - Domain 层不直接拼接或持有 URI
 * - 禁止在 Domain 层缓存 Uri 对象（可能导致权限失效）
 *
 * @see §5 核心数据结构
 */
data class NcmFileRef(
    val documentId: String,   // 仅保留 ID，不持有 DocumentFile
    val displayName: String,
    val size: Long
)
