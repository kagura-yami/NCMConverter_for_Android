/**
 * NCM Decrypt - 核心解密实现
 *
 * NCM 文件结构:
 * 1. Magic header: "CTENFDAM" (8 bytes)
 * 2. Key length (4 bytes, little-endian)
 * 3. Key data (AES-128-ECB encrypted)
 * 4. Meta length (4 bytes, little-endian)
 * 5. Meta data (AES-128-ECB encrypted, base64 encoded JSON)
 * 6. CRC (4 bytes, unused)
 * 7. Gap (5 bytes)
 * 8. Image size (4 bytes, little-endian)
 * 9. Image data (album cover)
 * 10. Audio data (RC4 encrypted)
 */

#include "ncm_decrypt.h"
#include <fstream>
#include <sstream>
#include <cstring>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "NcmDecrypt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace ncm {

// NCM magic header
static const uint8_t NCM_MAGIC[] = {0x43, 0x54, 0x45, 0x4E, 0x46, 0x44, 0x41, 0x4D}; // "CTENFDAM"

// AES core key for decrypting the RC4 key
static const uint8_t CORE_KEY[] = {
    0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
    0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57
};

// AES meta key for decrypting metadata
static const uint8_t META_KEY[] = {
    0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
    0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
};

// ==================== AES Implementation ====================

/**
 * AES S-Box
 */
static const uint8_t AES_SBOX[256] = {
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
};

/**
 * AES Inverse S-Box
 */
static const uint8_t AES_INV_SBOX[256] = {
    0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
    0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
    0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
    0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
    0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
    0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
    0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
    0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
    0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
    0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
    0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
    0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
    0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
    0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
    0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
    0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d
};

/**
 * AES round constants
 */
static const uint8_t RCON[11] = {
    0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36
};

/**
 * GF(2^8) multiplication
 */
static uint8_t gmul(uint8_t a, uint8_t b) {
    uint8_t p = 0;
    for (int i = 0; i < 8; i++) {
        if (b & 1) p ^= a;
        bool hi_bit = a & 0x80;
        a <<= 1;
        if (hi_bit) a ^= 0x1b;
        b >>= 1;
    }
    return p;
}

/**
 * AES-128-ECB context
 */
class AES128 {
public:
    AES128(const uint8_t* key) {
        keyExpansion(key);
    }

    void decryptBlock(const uint8_t* in, uint8_t* out) {
        uint8_t state[16];
        memcpy(state, in, 16);

        addRoundKey(state, 10);

        for (int round = 9; round >= 1; round--) {
            invShiftRows(state);
            invSubBytes(state);
            addRoundKey(state, round);
            invMixColumns(state);
        }

        invShiftRows(state);
        invSubBytes(state);
        addRoundKey(state, 0);

        memcpy(out, state, 16);
    }

private:
    uint8_t roundKeys[176]; // 11 * 16 bytes

    void keyExpansion(const uint8_t* key) {
        memcpy(roundKeys, key, 16);

        for (int i = 4; i < 44; i++) {
            uint8_t temp[4];
            memcpy(temp, &roundKeys[(i - 1) * 4], 4);

            if (i % 4 == 0) {
                // RotWord
                uint8_t t = temp[0];
                temp[0] = temp[1];
                temp[1] = temp[2];
                temp[2] = temp[3];
                temp[3] = t;

                // SubWord
                for (int j = 0; j < 4; j++) {
                    temp[j] = AES_SBOX[temp[j]];
                }

                // XOR with Rcon
                temp[0] ^= RCON[i / 4];
            }

            for (int j = 0; j < 4; j++) {
                roundKeys[i * 4 + j] = roundKeys[(i - 4) * 4 + j] ^ temp[j];
            }
        }
    }

    void addRoundKey(uint8_t* state, int round) {
        for (int i = 0; i < 16; i++) {
            state[i] ^= roundKeys[round * 16 + i];
        }
    }

    void invSubBytes(uint8_t* state) {
        for (int i = 0; i < 16; i++) {
            state[i] = AES_INV_SBOX[state[i]];
        }
    }

    void invShiftRows(uint8_t* state) {
        uint8_t temp;

        // Row 1: shift right by 1
        temp = state[13];
        state[13] = state[9];
        state[9] = state[5];
        state[5] = state[1];
        state[1] = temp;

        // Row 2: shift right by 2
        std::swap(state[2], state[10]);
        std::swap(state[6], state[14]);

        // Row 3: shift right by 3
        temp = state[3];
        state[3] = state[7];
        state[7] = state[11];
        state[11] = state[15];
        state[15] = temp;
    }

    void invMixColumns(uint8_t* state) {
        for (int c = 0; c < 4; c++) {
            uint8_t a[4];
            for (int i = 0; i < 4; i++) {
                a[i] = state[c * 4 + i];
            }

            state[c * 4 + 0] = gmul(a[0], 0x0e) ^ gmul(a[1], 0x0b) ^ gmul(a[2], 0x0d) ^ gmul(a[3], 0x09);
            state[c * 4 + 1] = gmul(a[0], 0x09) ^ gmul(a[1], 0x0e) ^ gmul(a[2], 0x0b) ^ gmul(a[3], 0x0d);
            state[c * 4 + 2] = gmul(a[0], 0x0d) ^ gmul(a[1], 0x09) ^ gmul(a[2], 0x0e) ^ gmul(a[3], 0x0b);
            state[c * 4 + 3] = gmul(a[0], 0x0b) ^ gmul(a[1], 0x0d) ^ gmul(a[2], 0x09) ^ gmul(a[3], 0x0e);
        }
    }
};

/**
 * AES-128-ECB decryption
 */
std::vector<uint8_t> aesEcbDecrypt(const uint8_t* data, size_t len, const uint8_t* key) {
    if (len == 0 || len % 16 != 0) {
        return {};
    }

    AES128 aes(key);
    std::vector<uint8_t> result(len);

    for (size_t i = 0; i < len; i += 16) {
        aes.decryptBlock(data + i, result.data() + i);
    }

    // Remove PKCS7 padding
    uint8_t padLen = result.back();
    if (padLen > 0 && padLen <= 16) {
        result.resize(len - padLen);
    }

    return result;
}

// ==================== RC4 Implementation ====================

/**
 * Build RC4 key box (256-byte S-box)
 */
void buildRc4KeyBox(const uint8_t* key, size_t keyLen, uint8_t* box) {
    for (int i = 0; i < 256; i++) {
        box[i] = static_cast<uint8_t>(i);
    }

    int j = 0;
    for (int i = 0; i < 256; i++) {
        j = (j + box[i] + key[i % keyLen]) & 0xFF;
        std::swap(box[i], box[j]);
    }
}

/**
 * NCM-specific RC4 stream cipher
 * Uses a modified algorithm specific to NCM
 */
void rc4NcmDecrypt(const uint8_t* keyBox, uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        size_t j = (i + 1) & 0xFF;
        data[i] ^= keyBox[(keyBox[j] + keyBox[(keyBox[j] + j) & 0xFF]) & 0xFF];
    }
}

// ==================== Base64 Implementation ====================

static const char BASE64_CHARS[] = 
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64Encode(const uint8_t* data, size_t len) {
    std::string result;
    result.reserve((len + 2) / 3 * 4);

    for (size_t i = 0; i < len; i += 3) {
        uint32_t n = static_cast<uint32_t>(data[i]) << 16;
        if (i + 1 < len) n |= static_cast<uint32_t>(data[i + 1]) << 8;
        if (i + 2 < len) n |= static_cast<uint32_t>(data[i + 2]);

        result += BASE64_CHARS[(n >> 18) & 0x3F];
        result += BASE64_CHARS[(n >> 12) & 0x3F];
        result += (i + 1 < len) ? BASE64_CHARS[(n >> 6) & 0x3F] : '=';
        result += (i + 2 < len) ? BASE64_CHARS[n & 0x3F] : '=';
    }

    return result;
}

std::vector<uint8_t> base64Decode(const std::string& encoded) {
    static const int DECODE_TABLE[256] = {
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
        52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
        15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
        -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
        41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1
    };

    std::vector<uint8_t> result;
    result.reserve(encoded.size() * 3 / 4);

    int val = 0, bits = -8;
    for (char c : encoded) {
        if (c == '=') break;
        int v = DECODE_TABLE[static_cast<uint8_t>(c)];
        if (v < 0) continue;
        val = (val << 6) | v;
        bits += 6;
        if (bits >= 0) {
            result.push_back(static_cast<uint8_t>((val >> bits) & 0xFF));
            bits -= 8;
        }
    }

    return result;
}

// ==================== JSON Helpers ====================

std::string escapeJson(const std::string& s) {
    std::string result;
    result.reserve(s.size());
    for (char c : s) {
        switch (c) {
            case '"': result += "\\\""; break;
            case '\\': result += "\\\\"; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default: result += c; break;
        }
    }
    return result;
}

std::string extractJsonString(const std::string& json, const std::string& key) {
    std::string searchKey = "\"" + key + "\"";
    size_t pos = json.find(searchKey);
    if (pos == std::string::npos) return "";

    pos = json.find(':', pos);
    if (pos == std::string::npos) return "";

    pos = json.find('"', pos);
    if (pos == std::string::npos) return "";

    size_t start = pos + 1;
    size_t end = start;
    while (end < json.size() && (json[end] != '"' || json[end - 1] == '\\')) {
        end++;
    }

    return json.substr(start, end - start);
}

int extractJsonInt(const std::string& json, const std::string& key) {
    std::string searchKey = "\"" + key + "\"";
    size_t pos = json.find(searchKey);
    if (pos == std::string::npos) return 0;

    pos = json.find(':', pos);
    if (pos == std::string::npos) return 0;

    pos++;
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) {
        pos++;
    }

    std::string numStr;
    while (pos < json.size() && (json[pos] >= '0' && json[pos] <= '9')) {
        numStr += json[pos++];
    }

    return numStr.empty() ? 0 : std::stoi(numStr);
}

std::vector<std::string> extractJsonArtists(const std::string& json) {
    std::vector<std::string> artists;

    // Find "artist" array
    size_t pos = json.find("\"artist\"");
    if (pos == std::string::npos) return artists;

    pos = json.find('[', pos);
    if (pos == std::string::npos) return artists;

    size_t end = json.find(']', pos);
    if (end == std::string::npos) return artists;

    std::string arr = json.substr(pos, end - pos + 1);

    // Parse nested arrays: [[name, id], [name, id], ...]
    size_t i = 1;
    while (i < arr.size()) {
        if (arr[i] == '[') {
            size_t nameStart = arr.find('"', i);
            if (nameStart == std::string::npos) break;
            nameStart++;
            size_t nameEnd = nameStart;
            while (nameEnd < arr.size() && (arr[nameEnd] != '"' || arr[nameEnd - 1] == '\\')) {
                nameEnd++;
            }
            if (nameEnd > nameStart) {
                artists.push_back(arr.substr(nameStart, nameEnd - nameStart));
            }
            i = arr.find(']', nameEnd);
            if (i == std::string::npos) break;
        }
        i++;
    }

    return artists;
}

// ==================== File Utilities ====================

std::string getBaseName(const std::string& path) {
    size_t lastSlash = path.find_last_of("/\\");
    std::string filename = (lastSlash == std::string::npos) ? path : path.substr(lastSlash + 1);

    size_t lastDot = filename.rfind('.');
    return (lastDot == std::string::npos) ? filename : filename.substr(0, lastDot);
}

bool writeFile(const std::string& path, const uint8_t* data, size_t len) {
    std::ofstream file(path, std::ios::binary);
    if (!file) return false;
    file.write(reinterpret_cast<const char*>(data), len);
    return file.good();
}

bool writeFile(const std::string& path, const std::string& content) {
    std::ofstream file(path);
    if (!file) return false;
    file << content;
    return file.good();
}

// ==================== Audio Format Detection ====================

std::string detectAudioFormat(const uint8_t* data, size_t len) {
    if (len < 4) return "";

    // FLAC: starts with "fLaC"
    if (data[0] == 0x66 && data[1] == 0x4C && data[2] == 0x61 && data[3] == 0x43) {
        return "flac";
    }

    // MP3: starts with ID3 tag or sync word
    if ((data[0] == 0x49 && data[1] == 0x44 && data[2] == 0x33) ||  // "ID3"
        (data[0] == 0xFF && (data[1] & 0xE0) == 0xE0)) {            // Sync word
        return "mp3";
    }

    return "";
}

// ==================== Main Decrypt Function ====================

int decryptNcmFile(const std::string& inputPath, const std::string& outputDir) {
    LOGI("Decrypting: %s", inputPath.c_str());

    // Open input file
    std::ifstream file(inputPath, std::ios::binary);
    if (!file) {
        LOGE("Cannot open file: %s", inputPath.c_str());
        return RESULT_FILE_READ_ERROR;
    }

    // Read magic header
    uint8_t magic[8];
    file.read(reinterpret_cast<char*>(magic), 8);
    if (memcmp(magic, NCM_MAGIC, 8) != 0) {
        LOGE("Invalid NCM magic header");
        return RESULT_INVALID_FORMAT;
    }

    // Skip 2 bytes (gap)
    file.seekg(2, std::ios::cur);

    // Read key length and key data
    uint32_t keyLen;
    file.read(reinterpret_cast<char*>(&keyLen), 4);
    if (keyLen == 0 || keyLen > 1024) {
        LOGE("Invalid key length: %u", keyLen);
        return RESULT_INVALID_FORMAT;
    }

    std::vector<uint8_t> keyData(keyLen);
    file.read(reinterpret_cast<char*>(keyData.data()), keyLen);

    // XOR key data with 0x64
    for (auto& b : keyData) {
        b ^= 0x64;
    }

    // Decrypt key with AES (CORE_KEY)
    std::vector<uint8_t> decryptedKey = aesEcbDecrypt(keyData.data(), keyData.size(), CORE_KEY);
    if (decryptedKey.empty()) {
        LOGE("Failed to decrypt key");
        return RESULT_DECRYPT_FAILED;
    }

    // Skip "neteasecloudmusic" prefix (17 bytes)
    if (decryptedKey.size() <= 17) {
        LOGE("Decrypted key too short");
        return RESULT_DECRYPT_FAILED;
    }
    std::vector<uint8_t> rc4Key(decryptedKey.begin() + 17, decryptedKey.end());

    // Build RC4 key box
    uint8_t keyBox[256];
    buildRc4KeyBox(rc4Key.data(), rc4Key.size(), keyBox);

    // Read meta length and meta data
    uint32_t metaLen;
    file.read(reinterpret_cast<char*>(&metaLen), 4);

    NcmMetadata metadata;
    std::string coverBase64;

    if (metaLen > 0 && metaLen < 1024 * 1024) {
        std::vector<uint8_t> metaData(metaLen);
        file.read(reinterpret_cast<char*>(metaData.data()), metaLen);

        // XOR with 0x63
        for (auto& b : metaData) {
            b ^= 0x63;
        }

        // Decode base64 (skip "163 key(Don't modify):" prefix - 22 bytes)
        std::string metaB64(reinterpret_cast<char*>(metaData.data()), metaData.size());
        if (metaB64.size() > 22) {
            metaB64 = metaB64.substr(22);
        }
        std::vector<uint8_t> metaDecoded = base64Decode(metaB64);

        // Decrypt with AES (META_KEY)
        std::vector<uint8_t> metaJson = aesEcbDecrypt(metaDecoded.data(), metaDecoded.size(), META_KEY);
        
        if (!metaJson.empty()) {
            // Skip "music:" prefix if present
            std::string jsonStr(reinterpret_cast<char*>(metaJson.data()), metaJson.size());
            if (jsonStr.find("music:") == 0) {
                jsonStr = jsonStr.substr(6);
            }

            LOGI("Metadata JSON: %s", jsonStr.substr(0, 200).c_str());

            // Parse metadata
            metadata.musicName = extractJsonString(jsonStr, "musicName");
            metadata.album = extractJsonString(jsonStr, "album");
            metadata.artists = extractJsonArtists(jsonStr);
            metadata.bitrate = extractJsonInt(jsonStr, "bitrate");
            
            // Get album pic URL (will be converted to base64 from embedded image)
            coverBase64 = extractJsonString(jsonStr, "albumPic");
        }
    }

    // Skip CRC (4 bytes) and gap (5 bytes)
    file.seekg(9, std::ios::cur);

    // Read image
    uint32_t imageSize;
    file.read(reinterpret_cast<char*>(&imageSize), 4);

    std::vector<uint8_t> imageData;
    if (imageSize > 0 && imageSize < 10 * 1024 * 1024) {
        imageData.resize(imageSize);
        file.read(reinterpret_cast<char*>(imageData.data()), imageSize);
        // Convert embedded image to base64
        coverBase64 = base64Encode(imageData.data(), imageData.size());
    }

    // Read audio data
    std::streampos audioStart = file.tellg();
    file.seekg(0, std::ios::end);
    std::streampos audioEnd = file.tellg();
    size_t audioLen = audioEnd - audioStart;
    file.seekg(audioStart);

    if (audioLen == 0) {
        LOGE("No audio data");
        return RESULT_DECRYPT_FAILED;
    }

    std::vector<uint8_t> audioData(audioLen);
    file.read(reinterpret_cast<char*>(audioData.data()), audioLen);
    file.close();

    // Decrypt audio with RC4
    rc4NcmDecrypt(keyBox, audioData.data(), audioData.size());

    // Detect format from audio header (§3.4: format 以实际输出音频扩展名为准)
    std::string format = detectAudioFormat(audioData.data(), audioData.size());
    if (format.empty()) {
        LOGE("Cannot detect audio format");
        return RESULT_DECRYPT_FAILED;
    }
    metadata.format = format;
    metadata.coverBase64 = coverBase64;

    LOGI("Detected format: %s", format.c_str());

    // Generate output paths
    std::string baseName = getBaseName(inputPath);
    std::string audioPath = outputDir + "/" + baseName + "." + format;
    std::string metaPath = outputDir + "/" + baseName + ".meta.json";

    // Write audio file
    if (!writeFile(audioPath, audioData.data(), audioData.size())) {
        LOGE("Failed to write audio file: %s", audioPath.c_str());
        return RESULT_FILE_READ_ERROR;
    }

    // Build and write metadata JSON
    std::ostringstream json;
    json << "{\n";
    json << "  \"musicName\": \"" << escapeJson(metadata.musicName) << "\",\n";
    json << "  \"album\": \"" << escapeJson(metadata.album) << "\",\n";
    json << "  \"artist\": [";
    for (size_t i = 0; i < metadata.artists.size(); i++) {
        if (i > 0) json << ", ";
        json << "[\"" << escapeJson(metadata.artists[i]) << "\", 0]";
    }
    json << "],\n";
    json << "  \"bitrate\": " << metadata.bitrate << ",\n";
    json << "  \"format\": \"" << metadata.format << "\",\n";
    json << "  \"albumPic\": \"" << metadata.coverBase64 << "\"\n";
    json << "}\n";

    if (!writeFile(metaPath, json.str())) {
        LOGE("Failed to write metadata file: %s", metaPath.c_str());
        // Non-fatal: audio was written successfully
    }

    LOGI("Decryption successful: %s", audioPath.c_str());
    return RESULT_SUCCESS;
}

} // namespace ncm
