/**
 * NCM Decrypt - 核心解密模块
 *
 * 职责 (per §3.4):
 * - AES 解密核心
 * - RC4 密钥扩展
 * - 音频数据提取
 * - Metadata JSON 输出
 *
 * 返回值:
 *   0  = 成功
 *  -1  = 文件读取失败
 *  -2  = 非法 NCM 格式
 *  -3  = 解密失败
 *
 * 输出文件:
 *   outputDir/xxx.mp3 (或 .flac，取决于源格式)
 *   outputDir/xxx.meta.json (含 title, artist, album, cover base64)
 */

#ifndef NCM_DECRYPT_H
#define NCM_DECRYPT_H

#include <string>
#include <vector>
#include <cstdint>

namespace ncm {

// 返回值常量
constexpr int RESULT_SUCCESS = 0;
constexpr int RESULT_FILE_READ_ERROR = -1;
constexpr int RESULT_INVALID_FORMAT = -2;
constexpr int RESULT_DECRYPT_FAILED = -3;

/**
 * NCM 文件元数据
 */
struct NcmMetadata {
    std::string musicName;
    std::vector<std::string> artists;
    std::string album;
    std::string format;         // "mp3" or "flac"
    std::string coverBase64;    // 封面图 base64 (可能为空)
    int bitrate;
};

/**
 * 解密 NCM 文件
 *
 * @param inputPath  输入文件路径 (cacheDir/input/xxx.ncm)
 * @param outputDir  输出目录路径 (cacheDir/output/)
 * @return 0=成功, -1=文件读取失败, -2=非法NCM格式, -3=解密失败
 */
int decryptNcmFile(const std::string& inputPath, const std::string& outputDir);

} // namespace ncm

#endif // NCM_DECRYPT_H
