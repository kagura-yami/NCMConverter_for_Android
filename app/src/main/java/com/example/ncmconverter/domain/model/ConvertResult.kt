package com.example.ncmconverter.domain.model

/**
 * 转换结果 (三态)
 *
 * @see §5 核心数据结构
 */
sealed class ConvertResult {
    data class Success(val outputPath: String) : ConvertResult()
    data class SuccessWithWarning(val outputPath: String, val warning: String) : ConvertResult()
    data class Skipped(val fileName: String) : ConvertResult()  // 因冲突策略跳过
    /** 覆盖回退：无法覆盖原文件（权限限制），已保存为新文件 */
    data class OverwriteFallback(val outputPath: String, val intendedFileName: String) : ConvertResult()
    data class Failed(val failure: ConvertFailure) : ConvertResult()
}
