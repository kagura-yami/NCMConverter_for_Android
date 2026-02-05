/**
 * JNI Bridge - NCM Decrypt JNI 入口
 *
 * 职责 (per §3.4):
 * - JNI 入口点
 * - 字符串转换
 * - 调用 C++ 解密核心
 *
 * JNI 签名:
 * native int decryptNcm(String inputPath, String outputDir)
 */

#include <jni.h>
#include <string>
#include "ncm_decrypt.h"

extern "C" {

/**
 * JNI entry point for NCM decryption
 *
 * @param env JNI environment
 * @param thiz Java object reference (unused)
 * @param inputPath Input file path (cacheDir/input/xxx.ncm)
 * @param outputDir Output directory path (cacheDir/output/)
 * @return 0=success, -1=file read error, -2=invalid format, -3=decrypt failed
 */
JNIEXPORT jint JNICALL
Java_com_example_ncmconverter_infra_NcmDecoder_decryptNcm(
        JNIEnv *env,
        jobject /* thiz */,
        jstring inputPath,
        jstring outputDir) {
    
    // Convert Java strings to C++ strings
    const char* inputPathCStr = env->GetStringUTFChars(inputPath, nullptr);
    const char* outputDirCStr = env->GetStringUTFChars(outputDir, nullptr);

    if (inputPathCStr == nullptr || outputDirCStr == nullptr) {
        if (inputPathCStr) env->ReleaseStringUTFChars(inputPath, inputPathCStr);
        if (outputDirCStr) env->ReleaseStringUTFChars(outputDir, outputDirCStr);
        return ncm::RESULT_FILE_READ_ERROR;
    }

    std::string inputPathStr(inputPathCStr);
    std::string outputDirStr(outputDirCStr);

    // Release Java strings
    env->ReleaseStringUTFChars(inputPath, inputPathCStr);
    env->ReleaseStringUTFChars(outputDir, outputDirCStr);

    // Call C++ decryption function
    return ncm::decryptNcmFile(inputPathStr, outputDirStr);
}

} // extern "C"
