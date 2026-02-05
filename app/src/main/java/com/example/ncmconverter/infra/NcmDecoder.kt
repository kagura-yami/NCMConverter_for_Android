package com.example.ncmconverter.infra

import android.content.Context
import com.example.ncmconverter.domain.model.NcmMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * NCM Decoder - JNI 桥接层
 *
 * 职责 (per §3.3):
 * - JNI 桥接，调用 C++ 解密
 * - 只接受绝对路径
 *
 * JNI 返回值与 Kotlin 映射 (per §3.4):
 * | JNI 返回值 | 说明 |
 * |------------|------|
 * | 0  | 成功 |
 * | -1 | 文件读取失败 → CorruptedFile |
 * | -2 | 非法 NCM 格式 → CorruptedFile |
 * | -3 | 解密失败 → DecryptFailed |
 *
 * 约束:
 * - JNI 调用加 Mutex，不允许并发解密 (§6.3)
 */
class NcmDecoder(private val context: Context) {

    companion object {
        private const val OUTPUT_DIR = "output"

        init {
            System.loadLibrary("ncmdecrypt")
        }
    }

    // Mutex to prevent concurrent JNI calls (§6.3)
    private val decodeMutex = Mutex()

    /**
     * Native 解密方法
     *
     * @param inputPath cacheDir/input/xxx.ncm 的绝对路径
     * @param outputDir cacheDir/output/ 的绝对路径
     * @return 0=成功, -1=文件读取失败, -2=非法NCM格式, -3=解密失败
     */
    private external fun decryptNcm(inputPath: String, outputDir: String): Int

    /**
     * 获取输出目录路径，不存在则创建
     */
    private fun getOutputDir(): File {
        val dir = File(context.cacheDir, OUTPUT_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 解密结果
     *
     * @param code JNI 返回码
     * @param audioPath 解密后的音频文件路径（仅成功时有效）
     * @param metadata 解析的 metadata（仅成功时有效）
     */
    data class DecryptResult(
        val code: Int,
        val audioPath: String?,
        val metadata: NcmMetadata?
    )

    /**
     * 解密 NCM 文件
     *
     * 【重要】format 以实际输出音频的扩展名为准 (§3.4)
     *
     * @param inputPath 输入文件的绝对路径 (cacheDir/input/xxx.ncm)
     * @return DecryptResult 包含返回码、音频路径、metadata
     */
    suspend fun decrypt(inputPath: String): DecryptResult = withContext(Dispatchers.IO) {
        decodeMutex.withLock {
            val outputDir = getOutputDir()
            val code = decryptNcm(inputPath, outputDir.absolutePath)

            if (code != 0) {
                return@withContext DecryptResult(code, null, null)
            }

            // 查找输出的音频文件和 metadata
            val inputFile = File(inputPath)
            val baseName = inputFile.nameWithoutExtension

            // 查找实际输出的音频文件（可能是 .mp3 或 .flac）
            val audioFile = findOutputAudioFile(outputDir, baseName)
            val metaFile = File(outputDir, "$baseName.meta.json")

            if (audioFile == null) {
                // 音频文件未找到，视为解密失败
                return@withContext DecryptResult(-3, null, null)
            }

            // 解析 metadata
            val metadata = if (metaFile.exists()) {
                parseMetadata(metaFile, audioFile)
            } else {
                // 无 metadata 文件，创建基本 metadata
                createBasicMetadata(audioFile)
            }

            DecryptResult(0, audioFile.absolutePath, metadata)
        }
    }

    /**
     * 查找输出的音频文件
     *
     * format 以实际输出文件扩展名为准 (§3.4 重要约束)
     */
    private fun findOutputAudioFile(outputDir: File, baseName: String): File? {
        // 按优先级查找：flac > mp3
        val flacFile = File(outputDir, "$baseName.flac")
        if (flacFile.exists()) return flacFile

        val mp3File = File(outputDir, "$baseName.mp3")
        if (mp3File.exists()) return mp3File

        return null
    }

    /**
     * 解析 metadata JSON 文件
     *
     * 【重要】format 字段以实际音频文件扩展名为准，忽略 JSON 中的声明 (§3.4)
     */
    private fun parseMetadata(metaFile: File, audioFile: File): NcmMetadata {
        val json = JSONObject(metaFile.readText())

        val artistArray = json.optJSONArray("artist")
        val artists = if (artistArray != null) {
            (0 until artistArray.length()).mapNotNull { i ->
                val item = artistArray.opt(i)
                when (item) {
                    is String -> item
                    is org.json.JSONArray -> item.optString(0)
                    else -> null
                }
            }
        } else {
            emptyList()
        }

        // format 以实际文件扩展名为准，而非 JSON 声明
        val actualFormat = audioFile.extension.lowercase()

        return NcmMetadata(
            musicName = json.optString("musicName", ""),
            artist = artists,
            album = json.optString("album", ""),
            format = actualFormat,
            coverBase64 = json.optString("albumPic", null),
            bitrate = json.optInt("bitrate", 0).takeIf { it > 0 }
        )
    }

    /**
     * 无 metadata 时创建基本信息
     */
    private fun createBasicMetadata(audioFile: File): NcmMetadata {
        return NcmMetadata(
            musicName = audioFile.nameWithoutExtension,
            artist = emptyList(),
            album = "",
            format = audioFile.extension.lowercase(),
            coverBase64 = null,
            bitrate = null
        )
    }

    /**
     * 清空 cacheDir/output/ 目录
     *
     * 用于取消操作后的清理 (§6.6)
     * 
     * 仅清理 cacheDir/output/ 目录 (§6.6 清理范围)
     */
    fun clearOutputDir() {
        val outputDir = getOutputDir()
        outputDir.listFiles()?.forEach { file ->
            file.delete()
        }
    }

    /**
     * 删除输出目录中的指定文件
     */
    fun deleteFromOutput(fileName: String): Boolean {
        val file = File(getOutputDir(), fileName)
        return !file.exists() || file.delete()
    }

    /**
     * 获取 cacheDir/output/ 目录的绝对路径
     */
    fun getOutputDirPath(): String = getOutputDir().absolutePath
}
