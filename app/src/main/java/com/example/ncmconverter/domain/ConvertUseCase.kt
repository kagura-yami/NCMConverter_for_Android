package com.example.ncmconverter.domain

import com.example.ncmconverter.domain.model.AppSettings
import com.example.ncmconverter.domain.model.ConflictPolicy
import com.example.ncmconverter.domain.model.ConvertFailure
import com.example.ncmconverter.domain.model.ConvertResult
import com.example.ncmconverter.domain.model.HistoryStatus
import com.example.ncmconverter.domain.model.NcmFileRef
import com.example.ncmconverter.infra.FileCopier
import com.example.ncmconverter.infra.HistoryRepository
import com.example.ncmconverter.infra.NcmDecoder
import com.example.ncmconverter.infra.OutputWriter
import com.example.ncmconverter.infra.SAFManager
import com.example.ncmconverter.infra.SettingsRepository
import com.example.ncmconverter.infra.TagWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException

/**
 * Convert UseCase - 批量转换控制
 *
 * 职责 (per §3.2):
 * - 批量控制
 * - 失败判定
 * - 是否中断
 * - 去重策略
 * - 重试策略（当前版本不实现重试，直接跳过失败文件）
 *
 * Domain 层负责所有业务逻辑判断 (per §3.2):
 * - 去重检测
 * - 并发控制
 * - 重试策略
 * - 中断决策
 */
class ConvertUseCase(
    private val safManager: SAFManager,
    private val fileCopier: FileCopier,
    private val ncmDecoder: NcmDecoder,
    private val tagWriter: TagWriter,
    private val outputWriter: OutputWriter,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository
) {

    /**
     * 批量转换进度
     *
     * @param current 当前处理的文件索引 (0-based)
     * @param total 总文件数
     * @param currentFile 当前处理的文件
     * @param result 当前文件的转换结果
     */
    data class ConvertProgress(
        val current: Int,
        val total: Int,
        val currentFile: NcmFileRef,
        val result: ConvertResult
    )

    /**
     * 批量转换最终结果
     *
     * @param successCount 成功数量
     * @param warningCount 成功但有警告的数量
     * @param failedCount 失败数量
     * @param skippedCount 跳过数量（冲突策略为 SKIP 时）
     * @param interrupted 是否被用户中断
     * @param fatalFailure 致命错误（如果有）
     */
    data class BatchResult(
        val successCount: Int,
        val warningCount: Int,
        val failedCount: Int,
        val skippedCount: Int,
        val interrupted: Boolean,
        val fatalFailure: ConvertFailure?
    )

    /**
     * 执行批量转换
     *
     * 串行执行 (per §6.3)，每个文件处理前检查 isActive 信号 (per §6.6)
     *
     * @param files 待转换的文件列表
     * @return Flow<ConvertProgress> 每个文件处理完成后发射进度
     */
    fun convertBatch(files: List<NcmFileRef>): Flow<ConvertProgress> = flow {
        val settings = settingsRepository.settingsFlow.first()

        for ((index, file) in files.withIndex()) {
            // 每个文件处理前检查 isActive (§6.6)
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("User cancelled")
            }

            val result = convertSingleFile(file, settings)
            
            // 记录历史
            recordHistory(file.displayName, result, settings.conflictPolicy)
            
            emit(ConvertProgress(index, files.size, file, result))

            // 检查是否为致命失败 (§6.5)
            if (result is ConvertResult.Failed && isFatalFailure(result.failure)) {
                // 致命失败，立即中断批量任务
                break
            }
        }
    }
    
    /**
     * 记录转换历史
     */
    private suspend fun recordHistory(
        sourceName: String,
        result: ConvertResult,
        conflictPolicy: ConflictPolicy
    ) {
        val (status, message, outputName, outputPath) = when (result) {
            is ConvertResult.Success -> {
                val name = result.outputPath.substringAfterLast("/")
                HistoryRecord(
                    HistoryStatus.SUCCESS,
                    "转换完成",
                    name,
                    result.outputPath
                )
            }
            is ConvertResult.SuccessWithWarning -> {
                val name = result.outputPath.substringAfterLast("/")
                HistoryRecord(
                    HistoryStatus.SUCCESS_WITH_WARNING,
                    "转换完成（${result.warning}）",
                    name,
                    result.outputPath
                )
            }
            is ConvertResult.Skipped -> {
                HistoryRecord(
                    HistoryStatus.SKIPPED,
                    "文件已存在，已跳过",
                    null,
                    null
                )
            }
            is ConvertResult.OverwriteFallback -> {
                val name = result.outputPath.substringAfterLast("/")
                HistoryRecord(
                    HistoryStatus.OVERWRITE_FALLBACK,
                    "无法覆盖，已保存为新文件",
                    name,
                    result.outputPath
                )
            }
            is ConvertResult.Failed -> {
                val errorMsg = when (val failure = result.failure) {
                    is ConvertFailure.CorruptedFile -> failure.msg
                    is ConvertFailure.DecryptFailed -> "解密失败 (code: ${failure.code})"
                    is ConvertFailure.DiskFull -> "存储空间不足"
                    is ConvertFailure.OutputDirUnwritable -> "输出目录不可写"
                    is ConvertFailure.PermissionLost -> "权限丢失"
                }
                HistoryRecord(
                    HistoryStatus.FAILED,
                    "转换失败：$errorMsg",
                    null,
                    null
                )
            }
        }
        
        historyRepository.addHistory(
            sourceName = sourceName,
            outputName = outputName,
            outputPath = outputPath,
            status = status,
            message = message,
            conflictPolicy = conflictPolicy.name
        )
    }
    
    /**
     * 历史记录临时数据类
     */
    private data class HistoryRecord(
        val status: HistoryStatus,
        val message: String,
        val outputName: String?,
        val outputPath: String?
    )

    /**
     * 转换单个文件
     *
     * 数据流 (per §4):
     * 1. 复制到 cacheDir/input/
     * 2. NDK 解密
     * 3. TagWriter 写入 metadata + 封面
     * 4. 写入公共 Music (原子性)
     * 5. 成功后删除原 NCM (通过 SAF)
     *
     * @param file 待转换的文件
     * @param settings 用户设置
     * @return ConvertResult
     */
    private suspend fun convertSingleFile(
        file: NcmFileRef,
        settings: AppSettings
    ): ConvertResult {
        var cachedOutputPath: String? = null
        var warning: String? = null

        // Step 1: 复制到 cacheDir/input/
        val cachedInputPath: String = try {
            fileCopier.copyToCache(file.documentId, file.displayName)
        } catch (e: IOException) {
            return handleIOException(e, file)
        } catch (e: IllegalStateException) {
            return ConvertResult.Failed(ConvertFailure.PermissionLost)
        }

        try {
            // Step 2: NDK 解密
            val decryptResult = ncmDecoder.decrypt(cachedInputPath)
            if (decryptResult.code != 0) {
                return mapDecryptError(decryptResult.code, file)
            }

            cachedOutputPath = decryptResult.audioPath
            val metadata = decryptResult.metadata

            if (cachedOutputPath == null || metadata == null) {
                return ConvertResult.Failed(
                    ConvertFailure.DecryptFailed(file, -3)
                )
            }

            // Step 3: TagWriter 写入 metadata + 封面
            val tagResult = tagWriter.writeTags(cachedOutputPath, metadata)
            if (!tagResult.success || tagResult.warning != null) {
                // TagWriter 失败归为 SuccessWithWarning (§3.3)
                warning = tagResult.warning
            }

            // Step 4: 确定最终文件名和写入策略
            val baseName = File(file.displayName).nameWithoutExtension
            val extension = metadata.format
            val originalFileName = "$baseName.$extension"

            val resolveResult = resolveFinalFileNameAndMode(originalFileName, settings.conflictPolicy)
            if (resolveResult == null) {
                // SKIP 策略且文件存在 - 视为成功，也删除原文件
                cleanupCacheFiles(cachedInputPath, cachedOutputPath)
                
                // 跳过也算成功，删除原 NCM 文件
                if (settings.autoDeleteSource) {
                    android.util.Log.d("ConvertUseCase", "SKIP: file exists, deleting source file: ${file.documentId}")
                    val deleted = safManager.deleteDocument(file.documentId)
                    if (!deleted) {
                        android.util.Log.w("ConvertUseCase", "SKIP: Failed to delete source file: ${file.displayName}")
                    }
                }
                
                return ConvertResult.Skipped(originalFileName)
            }
            
            val (finalFileName, overwrite) = resolveResult

            // Step 5: 写入公共 Music (原子性)
            val writeResult = outputWriter.writeToMusic(cachedOutputPath, finalFileName, overwrite)
            if (!writeResult.success) {
                return handleWriteError(writeResult.error, file)
            }

            // Step 6: 成功后删除原 NCM (通过 SAF)
            if (settings.autoDeleteSource) {
                android.util.Log.d("ConvertUseCase", "autoDeleteSource is enabled, attempting to delete: ${file.documentId}")
                val deleted = safManager.deleteDocument(file.documentId)
                android.util.Log.d("ConvertUseCase", "delete result: $deleted")
                if (!deleted) {
                    // 删除失败记录 warning，不阻塞 (§6.7)
                    warning = combineWarnings(warning, "Failed to delete source file")
                    android.util.Log.w("ConvertUseCase", "Failed to delete source file: ${file.displayName}")
                }
            } else {
                android.util.Log.d("ConvertUseCase", "autoDeleteSource is disabled, skipping delete")
            }

            // 清理缓存文件
            cleanupCacheFiles(cachedInputPath, cachedOutputPath)

            // 返回结果
            val outputPath = writeResult.outputPath ?: ""
            
            // 检查是否发生了覆盖回退
            if (writeResult.overwriteFallback) {
                return ConvertResult.OverwriteFallback(outputPath, finalFileName)
            }
            
            return if (warning != null) {
                ConvertResult.SuccessWithWarning(outputPath, warning)
            } else {
                ConvertResult.Success(outputPath)
            }

        } catch (e: CancellationException) {
            // 用户取消，清理临时文件 (§6.6)
            cleanupCacheFiles(cachedInputPath, cachedOutputPath)
            throw e
        } catch (e: Exception) {
            // 未预期的异常
            cleanupCacheFiles(cachedInputPath, cachedOutputPath)
            return ConvertResult.Failed(
                ConvertFailure.CorruptedFile(file, "Unexpected error: ${e.message}")
            )
        }
    }

    /**
     * 解析最终文件名（处理去重与冲突）
     *
     * 去重与冲突策略 (per §6.4):
     * - 检测时机：转换前检测 Music 目录是否已存在目标文件
     * - 判定标准：按文件名（含扩展名）匹配
     * - 冲突选项：跳过 / 覆盖 / 重命名
     * - 处理层级：Domain 层统一处理
     *
     * @param originalFileName 原始文件名
     * @param conflictPolicy 冲突策略
     * @return Pair(最终文件名, 是否覆写)，如果策略为 SKIP 且文件存在则返回 null
     */
    private suspend fun resolveFinalFileNameAndMode(
        originalFileName: String,
        conflictPolicy: ConflictPolicy
    ): Pair<String, Boolean>? {
        android.util.Log.d("ConvertUseCase", 
            "resolveFinalFileNameAndMode: file='$originalFileName', policy=$conflictPolicy")
        
        val exists = outputWriter.fileExists(originalFileName)
        android.util.Log.d("ConvertUseCase", 
            "resolveFinalFileNameAndMode: fileExists=$exists")

        if (!exists) {
            android.util.Log.d("ConvertUseCase", 
                "resolveFinalFileNameAndMode: file does not exist, returning original name")
            return Pair(originalFileName, false)
        }

        return when (conflictPolicy) {
            ConflictPolicy.SKIP -> {
                android.util.Log.d("ConvertUseCase", 
                    "resolveFinalFileNameAndMode: SKIP policy, returning null")
                null
            }
            ConflictPolicy.OVERWRITE -> {
                android.util.Log.d("ConvertUseCase", 
                    "resolveFinalFileNameAndMode: OVERWRITE policy, will overwrite existing file")
                // 不再删除，而是设置 overwrite=true 让 OutputWriter 直接覆写
                Pair(originalFileName, true)
            }
            ConflictPolicy.RENAME -> {
                android.util.Log.d("ConvertUseCase", 
                    "resolveFinalFileNameAndMode: RENAME policy, generating unique name")
                Pair(generateUniqueFileName(originalFileName), false)
            }
        }
    }

    /**
     * 生成唯一文件名
     *
     * 格式：song(1).mp3, song(2).mp3, ...
     */
    private suspend fun generateUniqueFileName(originalFileName: String): String {
        val file = File(originalFileName)
        val baseName = file.nameWithoutExtension
        val extension = file.extension

        var counter = 1
        var candidate: String
        do {
            candidate = "$baseName($counter).$extension"
            counter++
        } while (outputWriter.fileExists(candidate))

        return candidate
    }

    /**
     * 映射 JNI 解密错误码到 ConvertFailure
     *
     * JNI 返回值映射 (per §3.4):
     * | -1 | CorruptedFile | 文件 IO 错误 |
     * | -2 | CorruptedFile | 非法 NCM 格式 |
     * | -3 | DecryptFailed | 解密算法失败 |
     */
    private fun mapDecryptError(code: Int, file: NcmFileRef): ConvertResult.Failed {
        return when (code) {
            -1 -> ConvertResult.Failed(
                ConvertFailure.CorruptedFile(file, "File IO error")
            )
            -2 -> ConvertResult.Failed(
                ConvertFailure.CorruptedFile(file, "Invalid NCM format")
            )
            -3 -> ConvertResult.Failed(
                ConvertFailure.DecryptFailed(file, code)
            )
            else -> ConvertResult.Failed(
                ConvertFailure.DecryptFailed(file, code)
            )
        }
    }

    /**
     * 处理 IO 异常
     *
     * 判断是否为致命错误（磁盘满）
     */
    private fun handleIOException(e: IOException, file: NcmFileRef): ConvertResult.Failed {
        val message = e.message?.lowercase() ?: ""
        return if (message.contains("no space") || message.contains("disk full")) {
            ConvertResult.Failed(ConvertFailure.DiskFull)
        } else {
            ConvertResult.Failed(
                ConvertFailure.CorruptedFile(file, "IO error: ${e.message}")
            )
        }
    }

    /**
     * 处理写入错误
     *
     * 判断是否为致命错误（权限丢失、目录不可写）
     */
    private fun handleWriteError(error: String?, file: NcmFileRef): ConvertResult.Failed {
        val message = error?.lowercase() ?: ""
        return when {
            message.contains("permission") -> ConvertResult.Failed(ConvertFailure.PermissionLost)
            message.contains("no space") || message.contains("disk full") -> {
                ConvertResult.Failed(ConvertFailure.DiskFull)
            }
            message.contains("not writable") || message.contains("read-only") -> {
                ConvertResult.Failed(ConvertFailure.OutputDirUnwritable)
            }
            else -> ConvertResult.Failed(
                ConvertFailure.CorruptedFile(file, "Write error: $error")
            )
        }
    }

    /**
     * 判断是否为致命失败
     *
     * 致命失败 (per §6.5): 立即中断批量任务
     */
    private fun isFatalFailure(failure: ConvertFailure): Boolean {
        return when (failure) {
            is ConvertFailure.DiskFull -> true
            is ConvertFailure.PermissionLost -> true
            is ConvertFailure.OutputDirUnwritable -> true
            // 可恢复失败
            is ConvertFailure.CorruptedFile -> false
            is ConvertFailure.DecryptFailed -> false
        }
    }

    /**
     * 合并警告信息
     */
    private fun combineWarnings(existing: String?, new: String): String {
        return if (existing == null) new else "$existing; $new"
    }

    /**
     * 清理缓存文件
     *
     * cacheDir 清理范围 (per §6.6):
     * - 仅清理 cacheDir/input/ 和 cacheDir/output/ 中的指定文件
     */
    private fun cleanupCacheFiles(inputPath: String?, outputPath: String?) {
        inputPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        outputPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
            // 同时清理 .meta.json 文件
            val metaFile = File(path.substringBeforeLast('.') + ".meta.json")
            if (metaFile.exists()) metaFile.delete()
        }
    }

    /**
     * 清理所有缓存
     *
     * 用于用户取消后清理 (per §6.6)
     */
    fun clearAllCache() {
        fileCopier.clearInputDir()
        ncmDecoder.clearOutputDir()
    }

    /**
     * 统计批量结果
     *
     * 辅助方法，用于 UI 显示
     */
    fun summarizeResults(progressList: List<ConvertProgress>): BatchResult {
        var success = 0
        var warning = 0
        var failed = 0
        var skipped = 0
        var fatalFailure: ConvertFailure? = null

        for (progress in progressList) {
            when (val result = progress.result) {
                is ConvertResult.Success -> success++
                is ConvertResult.SuccessWithWarning -> warning++
                is ConvertResult.OverwriteFallback -> warning++  // 算作 warning
                is ConvertResult.Skipped -> skipped++
                is ConvertResult.Failed -> {
                    failed++
                    if (isFatalFailure(result.failure)) {
                        fatalFailure = result.failure
                    }
                }
            }
        }

        return BatchResult(
            successCount = success,
            warningCount = warning,
            failedCount = failed,
            skippedCount = skipped,
            interrupted = false,
            fatalFailure = fatalFailure
        )
    }
}
