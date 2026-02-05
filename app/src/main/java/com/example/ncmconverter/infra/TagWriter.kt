package com.example.ncmconverter.infra

import android.util.Base64
import com.example.ncmconverter.domain.model.NcmMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Tag Writer - 写入音频元数据和封面
 *
 * 职责 (per §3.3):
 * - 写入 ID3v2/Vorbis Comment + 封面
 * - 使用 JAudioTagger
 *
 * 【重要约束】(per §3.3):
 * 失败时不视为转换失败，归类为 SuccessWithWarning
 * 调用方负责处理 TagResult.warning
 *
 * 【Android 兼容性】:
 * 使用 StandardArtwork.setBinaryData() 直接操作字节数组，
 * 绕过 ArtworkFactory 对 javax.imageio.ImageIO 的依赖。
 */
class TagWriter {

    init {
        // Disable JAudioTagger verbose logging
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    /**
     * Tag 写入结果
     *
     * @param success 是否写入成功
     * @param warning 警告信息（仅失败时有值）
     * @param outputPath 输出文件路径
     */
    data class TagResult(
        val success: Boolean,
        val warning: String?,
        val outputPath: String
    )

    /**
     * 写入 metadata 和封面到音频文件
     *
     * 【重要】失败时返回 warning，不抛出异常 (§3.3)
     *
     * @param audioPath 待写入的音频文件路径
     * @param metadata NCM metadata
     * @return TagResult 包含结果状态和可能的警告
     */
    suspend fun writeTags(audioPath: String, metadata: NcmMetadata): TagResult = 
        withContext(Dispatchers.IO) {
            val audioFile = File(audioPath)

            try {
                val audio = AudioFileIO.read(audioFile)
                val tag = audio.tagOrCreateAndSetDefault

                // Write basic metadata
                if (metadata.musicName.isNotEmpty()) {
                    tag.setField(FieldKey.TITLE, metadata.musicName)
                }

                if (metadata.artist.isNotEmpty()) {
                    tag.setField(FieldKey.ARTIST, metadata.artist.joinToString("; "))
                }

                if (metadata.album.isNotEmpty()) {
                    tag.setField(FieldKey.ALBUM, metadata.album)
                }

                // Write cover art
                val coverWarning = writeCoverArt(tag, metadata.coverBase64, audioPath)

                // Commit changes
                audio.commit()

                if (coverWarning != null) {
                    TagResult(
                        success = true,
                        warning = coverWarning,
                        outputPath = audioPath
                    )
                } else {
                    TagResult(
                        success = true,
                        warning = null,
                        outputPath = audioPath
                    )
                }

            } catch (e: Exception) {
                // Tag write failed - return warning, not error (§3.3)
                TagResult(
                    success = false,
                    warning = "Failed to write tags: ${e.message}",
                    outputPath = audioPath
                )
            }
        }

    /**
     * 写入封面图 (Android 兼容版本)
     *
     * 【v1 平台兼容性策略】:
     * - MP3 (ID3v2): 完整写入封面
     * - FLAC (Vorbis Comment): 跳过封面写入，记录 warning
     *   原因：jaudiotagger 在 FLAC 封面路径 (FlacTag.createField → StandardArtwork.getImage())
     *        内部强制依赖 javax.imageio.ImageIO，Android 运行时不存在该类
     *
     * 符合 §3.3 设计约束：TagWriter 失败不阻塞转换主流程
     *
     * @param tag JAudioTagger tag 对象
     * @param coverBase64 封面图片 Base64 编码
     * @param audioPath 音频文件路径（用于检测格式）
     * @return 警告信息（如果封面写入失败或跳过）
     */
    private fun writeCoverArt(
        tag: org.jaudiotagger.tag.Tag,
        coverBase64: String?,
        audioPath: String
    ): String? {
        if (coverBase64.isNullOrEmpty()) {
            return null
        }

        // v1 策略：FLAC 跳过封面写入
        val isFlac = audioPath.lowercase().endsWith(".flac")
        if (isFlac) {
            return "FLAC cover art skipped (v1: jaudiotagger Android incompatibility)"
        }

        return try {
            val imageBytes = Base64.decode(coverBase64, Base64.DEFAULT)
            
            // 检测图片格式并设置 MIME 类型
            val mimeType = detectImageMimeType(imageBytes)
            
            // 使用 StandardArtwork 直接操作字节数组，绕过 ImageIO
            // pictureType = 3 是 ID3v2 规范定义的 Front Cover 类型
            val artwork = StandardArtwork().apply {
                binaryData = imageBytes
                this.mimeType = mimeType
                pictureType = 3  // ID3v2: 0x03 = Front Cover
            }
            
            tag.setField(artwork)
            null
        } catch (e: Exception) {
            "Failed to write cover art: ${e.message}"
        }
    }

    /**
     * 通过 magic bytes 检测图片 MIME 类型
     */
    private fun detectImageMimeType(imageBytes: ByteArray): String {
        if (imageBytes.size < 4) {
            return "image/jpeg" // fallback
        }

        // JPEG: FF D8 FF
        if (imageBytes[0] == 0xFF.toByte() && 
            imageBytes[1] == 0xD8.toByte() && 
            imageBytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }

        // PNG: 89 50 4E 47
        if (imageBytes[0] == 0x89.toByte() && 
            imageBytes[1] == 0x50.toByte() && 
            imageBytes[2] == 0x4E.toByte() && 
            imageBytes[3] == 0x47.toByte()) {
            return "image/png"
        }

        // GIF: 47 49 46
        if (imageBytes[0] == 0x47.toByte() && 
            imageBytes[1] == 0x49.toByte() && 
            imageBytes[2] == 0x46.toByte()) {
            return "image/gif"
        }

        // WebP: 52 49 46 46 ... 57 45 42 50
        if (imageBytes.size >= 12 &&
            imageBytes[0] == 0x52.toByte() && // R
            imageBytes[1] == 0x49.toByte() && // I
            imageBytes[2] == 0x46.toByte() && // F
            imageBytes[3] == 0x46.toByte() && // F
            imageBytes[8] == 0x57.toByte() && // W
            imageBytes[9] == 0x45.toByte() && // E
            imageBytes[10] == 0x42.toByte() && // B
            imageBytes[11] == 0x50.toByte()) { // P
            return "image/webp"
        }

        // BMP: 42 4D
        if (imageBytes[0] == 0x42.toByte() && 
            imageBytes[1] == 0x4D.toByte()) {
            return "image/bmp"
        }

        return "image/jpeg" // fallback to JPEG
    }
}
