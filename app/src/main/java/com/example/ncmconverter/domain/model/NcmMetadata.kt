package com.example.ncmconverter.domain.model

/**
 * 解密输出 metadata
 *
 * 【重要】format 可信来源：
 * NcmMetadata.format 以 NDK 解密后**实际输出音频的扩展名**为准，
 * 不依赖原文件名或 NCM 内嵌 metadata 声明字段。
 *
 * 原因：存在历史 NCM 样本，其 metadata 声明为 mp3，实际音频为 flac。
 *
 * @see §5 核心数据结构
 */
data class NcmMetadata(
    val musicName: String,
    val artist: List<String>,
    val album: String,
    val format: String,        // "mp3" or "flac"
    val coverBase64: String?,  // 封面图 base64
    val bitrate: Int?
)
