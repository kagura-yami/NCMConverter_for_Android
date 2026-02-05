package com.example.ncmconverter.infra

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * File Copier - 从 SAF URI 复制文件到 cacheDir
 *
 * 职责 (per §3.3):
 * - 从 SAF URI 复制到 cacheDir/input/
 *
 * 约束:
 * - 每个文件仅在需要时 openInputStream，用完立即关闭 (§6.1)
 * - NDK 只接受普通文件路径，需先复制到 cacheDir (§1.2)
 */
class FileCopier(
    private val context: Context,
    private val safManager: SAFManager
) {

    companion object {
        private const val INPUT_DIR = "input"
        private const val BUFFER_SIZE = 8192
    }

    /**
     * 获取输入目录路径，不存在则创建
     */
    private fun getInputDir(): File {
        val dir = File(context.cacheDir, INPUT_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 复制 NCM 文件到 cacheDir/input/
     *
     * @param documentId SAF 文档 ID
     * @param displayName 文件显示名称
     * @return 复制后的本地文件绝对路径
     * @throws IOException 如果复制失败
     * @throws IllegalStateException 如果无法打开输入流
     */
    suspend fun copyToCache(documentId: String, displayName: String): String = 
        withContext(Dispatchers.IO) {
            val inputDir = getInputDir()
            val targetFile = File(inputDir, displayName)

            // 如果目标文件已存在，先删除（可能是之前失败的残留）
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val inputStream = safManager.openInputStream(documentId)
                ?: throw IllegalStateException("Cannot open input stream for documentId: $documentId")

            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

            targetFile.absolutePath
        }

    /**
     * 删除 cacheDir/input/ 中的指定文件
     *
     * @param fileName 文件名
     * @return true 如果删除成功或文件不存在
     */
    fun deleteFromCache(fileName: String): Boolean {
        val file = File(getInputDir(), fileName)
        return !file.exists() || file.delete()
    }

    /**
     * 清空 cacheDir/input/ 目录
     *
     * 用于取消操作后的清理 (§6.6)
     * 
     * 仅清理 cacheDir/input/ 目录，不清理 cacheDir 根目录 (§6.6 清理范围)
     */
    fun clearInputDir() {
        val inputDir = getInputDir()
        inputDir.listFiles()?.forEach { file ->
            file.delete()
        }
    }

    /**
     * 获取 cacheDir/input/ 目录的绝对路径
     */
    fun getInputDirPath(): String = getInputDir().absolutePath
}
