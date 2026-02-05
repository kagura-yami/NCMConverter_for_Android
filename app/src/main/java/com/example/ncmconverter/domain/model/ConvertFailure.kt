package com.example.ncmconverter.domain.model

/**
 * 失败类型
 *
 * 分为两类：
 * - 可恢复失败：跳过当前文件，继续下一个（不中断批量）
 * - 致命失败：立即中断批量任务
 *
 * @see §5 核心数据结构
 * @see §6.5 失败与中断策略
 */
sealed class ConvertFailure {
    // ===== 可恢复失败 (不中断批量) =====
    data class CorruptedFile(val file: NcmFileRef, val msg: String) : ConvertFailure()
    data class DecryptFailed(val file: NcmFileRef, val code: Int) : ConvertFailure()

    // ===== 致命失败 (立即中断) =====
    object DiskFull : ConvertFailure()
    object PermissionLost : ConvertFailure()
    object OutputDirUnwritable : ConvertFailure()
}
