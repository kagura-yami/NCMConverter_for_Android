package com.example.ncmconverter.infra

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Output Writer - 写入公共 Music 目录
 *
 * 职责 (per §3.3):
 * - 写入公共 Music 目录
 * - 处理原子写入
 *
 * 【重要约束】(per §3.3):
 * - 只负责写入与重命名，不负责冲突决策（由 Domain 层提前决定最终文件名）
 * - 若 .tmp 插入成功但 rename/update 失败，必须删除孤立 .tmp 文件
 *
 * 【Android MediaStore 行为注意】:
 * MediaStore 在 insert 时若 DISPLAY_NAME 已存在，会自动重命名为 (1)/(2)，
 * 不抛异常。因此冲突检测和处理必须在 insert 之前完成。
 *
 * 【RELATIVE_PATH 查询注意】:
 * 不同 Android 版本可能返回 "Music" 或 "Music/"，使用 LIKE 查询更可靠。
 *
 * 原子写入策略 (per §4 数据流 + §8 风险应对):
 * 1. 先插入 .tmp 文件（IS_PENDING=1）
 * 2. 写入音频内容
 * 3. 成功后 update 改名并取消 pending
 * 4. 失败时 delete 孤立 .tmp
 */
class OutputWriter(private val context: Context) {

    companion object {
        private const val TMP_SUFFIX = ".tmp"
        // Music 目录的 RELATIVE_PATH 可能是 "Music" 或 "Music/"
        private const val MUSIC_PATH_PREFIX = "Music"
    }

    /**
     * 写入结果
     *
     * @param success 是否写入成功
     * @param outputPath 最终输出路径（仅成功时有效）
     * @param error 错误信息（仅失败时有效）
     * @param overwriteFallback 是否发生了覆盖回退（权限限制导致无法覆盖，创建了新文件）
     */
    data class WriteResult(
        val success: Boolean,
        val outputPath: String?,
        val error: String?,
        val overwriteFallback: Boolean = false
    )

    /**
     * 检查 Music 目录是否存在目标文件
     *
     * 【注意】此方法仅返回是否存在，冲突决策由 Domain 层处理
     * 
     * 使用双重检测：MediaStore 查询 + 文件系统直接检测
     * MediaStore 可能有缓存延迟，文件系统是实时的
     *
     * @param fileName 目标文件名（含扩展名）
     * @return true 如果文件已存在
     */
    suspend fun fileExists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        // 方法1: MediaStore 查询
        val mediaStoreExists = queryFileUri(fileName) != null
        android.util.Log.d("OutputWriter", "fileExists: MediaStore query result=$mediaStoreExists")
        
        // 方法2: 文件系统直接检测（备选，仅当 MediaStore 返回 false 时）
        if (!mediaStoreExists) {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val file = File(musicDir, fileName)
            val fileSystemExists = file.exists()
            android.util.Log.d("OutputWriter", 
                "fileExists: FileSystem check path='${file.absolutePath}', exists=$fileSystemExists")
            
            if (fileSystemExists) {
                android.util.Log.w("OutputWriter", 
                    "fileExists: MediaStore missed the file but FileSystem found it!")
                return@withContext true
            }
        }
        
        mediaStoreExists
    }

    /**
     * 查询文件的 Uri
     *
     * 使用 LIKE 查询 RELATIVE_PATH 以兼容不同 Android 版本
     * （有些返回 "Music"，有些返回 "Music/"）
     *
     * @param fileName 文件名
     * @return 文件 Uri，不存在则返回 null
     */
    private fun queryFileUri(fileName: String): Uri? {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        
        // 只匹配文件名，后面在代码中过滤 RELATIVE_PATH
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        android.util.Log.d("OutputWriter", "queryFileUri: searching for '$fileName'")

        return context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            android.util.Log.d("OutputWriter", "queryFileUri: found ${cursor.count} results")
            
            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                ) ?: ""
                val displayName = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                ) ?: ""
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                
                android.util.Log.d("OutputWriter", 
                    "queryFileUri: id=$id, displayName='$displayName', relativePath='$relativePath'")
                
                // 检查是否在 Music 目录（宽松匹配）
                // 可能的值: "Music", "Music/", "Music/子目录/", "" 等
                // 我们只接受 Music 根目录的文件
                val normalizedPath = relativePath.trim().trimEnd('/')
                val isInMusicRoot = normalizedPath.equals(MUSIC_PATH_PREFIX, ignoreCase = true) ||
                                    normalizedPath.isEmpty() // 有些设备返回空字符串表示根目录
                
                android.util.Log.d("OutputWriter", 
                    "queryFileUri: normalizedPath='$normalizedPath', isInMusicRoot=$isInMusicRoot")
                
                if (isInMusicRoot) {
                    android.util.Log.d("OutputWriter", "queryFileUri: MATCH FOUND, returning Uri")
                    return@use Uri.withAppendedPath(collection, id.toString())
                } else if (normalizedPath.startsWith(MUSIC_PATH_PREFIX, ignoreCase = true)) {
                    // 在 Music 子目录，比如 Music/NetEase，不算匹配
                    android.util.Log.d("OutputWriter", 
                        "queryFileUri: file is in Music subdirectory, not root - skipping")
                } else {
                    android.util.Log.d("OutputWriter", 
                        "queryFileUri: path not in Music directory - skipping")
                }
            }
            android.util.Log.d("OutputWriter", "queryFileUri: no match in Music root directory")
            null
        }
    }

    /**
     * 写入音频文件到公共 Music 目录
     *
     * @param sourcePath 源文件路径（cacheDir 中的文件）
     * @param finalFileName 最终输出文件名（由 Domain 层决定，含扩展名）
     * @param overwrite 是否覆写现有文件（OVERWRITE 策略时为 true）
     * @return WriteResult
     */
    suspend fun writeToMusic(
        sourcePath: String, 
        finalFileName: String,
        overwrite: Boolean = false
    ): WriteResult = withContext(Dispatchers.IO) {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            return@withContext WriteResult(
                success = false,
                outputPath = null,
                error = "Source file does not exist: $sourcePath"
            )
        }

        android.util.Log.d("OutputWriter", 
            "writeToMusic: file='$finalFileName', overwrite=$overwrite")

        // 如果是覆写模式，尝试直接覆写现有文件
        if (overwrite) {
            val overwriteResult = tryOverwrite(sourceFile, finalFileName)
            if (overwriteResult != null) {
                if (overwriteResult.success) {
                    return@withContext overwriteResult
                }
                // 如果是权限问题，回退到创建新文件
                if (overwriteResult.error == "PERMISSION_DENIED") {
                    android.util.Log.w("OutputWriter", 
                        "writeToMusic: cannot overwrite (permission denied), falling back to create new file")
                    // 回退到创建新文件，MediaStore 可能会自动重命名
                    val fallbackResult = createNewFile(sourceFile, finalFileName)
                    // 标记为覆盖回退
                    return@withContext fallbackResult.copy(overwriteFallback = true)
                }
                // 其他错误直接返回
                return@withContext overwriteResult
            }
            // overwriteResult == null 表示没找到现有文件，直接创建新文件
        }

        // 正常创建新文件流程（使用 .tmp 原子写入）
        return@withContext createNewFile(sourceFile, finalFileName)
    }

    /**
     * 尝试覆写现有文件
     *
     * @return WriteResult 如果找到现有文件，null 如果没找到
     */
    private fun tryOverwrite(sourceFile: File, fileName: String): WriteResult? {
        val existingUri = queryFileUri(fileName)
        if (existingUri != null) {
            android.util.Log.d("OutputWriter", 
                "tryOverwrite: found existing uri=$existingUri")
            return overwriteExistingFile(sourceFile, existingUri, fileName)
        }
        
        // 检查文件系统是否存在（MediaStore 可能没索引到）
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val existingFile = File(musicDir, fileName)
        if (existingFile.exists()) {
            android.util.Log.d("OutputWriter", 
                "tryOverwrite: file exists in filesystem but not in MediaStore, scanning...")
            val scannedUri = scanFileSyncAndGetUri(existingFile.absolutePath)
            if (scannedUri != null) {
                android.util.Log.d("OutputWriter", 
                    "tryOverwrite: scanned, uri=$scannedUri")
                return overwriteExistingFile(sourceFile, scannedUri, fileName)
            }
        }
        
        android.util.Log.d("OutputWriter", "tryOverwrite: no existing file found")
        return null
    }

    /**
     * 覆写现有文件
     *
     * 直接打开现有文件的 Uri 并写入新内容
     * 这避免了删除-创建导致的 MediaStore 索引不同步问题
     *
     * 【注意】Android 10+ 分区存储限制：
     * 应用只能修改自己创建的文件。尝试覆写其他来源的文件会抛出 SecurityException。
     * 此时返回 error="PERMISSION_DENIED"，让调用方回退到创建新文件。
     */
    private fun overwriteExistingFile(
        sourceFile: File,
        existingUri: Uri,
        fileName: String
    ): WriteResult {
        android.util.Log.d("OutputWriter", "overwriteExistingFile: uri=$existingUri")
        
        try {
            // 使用 "wt" 模式打开，会截断文件并写入
            context.contentResolver.openOutputStream(existingUri, "wt")?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Cannot open output stream for existing file")
            
            android.util.Log.d("OutputWriter", "overwriteExistingFile: success")
            return WriteResult(
                success = true,
                outputPath = "${Environment.DIRECTORY_MUSIC}/$fileName",
                error = null
            )
        } catch (e: SecurityException) {
            // Android 10+ 分区存储限制：无法覆写非本应用创建的文件
            android.util.Log.w("OutputWriter", 
                "overwriteExistingFile: SecurityException - cannot overwrite file not owned by this app", e)
            return WriteResult(
                success = false,
                outputPath = null,
                error = "PERMISSION_DENIED"  // 特殊标记，让调用方回退
            )
        } catch (e: Exception) {
            android.util.Log.e("OutputWriter", "overwriteExistingFile: failed", e)
            return WriteResult(
                success = false,
                outputPath = null,
                error = "Failed to overwrite file: ${e.message}"
            )
        }
    }

    /**
     * 创建新文件（使用 .tmp 原子写入）
     *
     * 原子写入流程:
     * 1. 生成唯一 .tmp 文件名
     * 2. 插入 .tmp 文件到 MediaStore（IS_PENDING=1）
     * 3. 写入音频内容
     * 4. 重命名为最终文件名并取消 pending
     * 5. 任何失败都清理孤立 .tmp
     */
    private fun createNewFile(sourceFile: File, finalFileName: String): WriteResult {
        android.util.Log.d("OutputWriter", "createNewFile: file='$finalFileName'")
        
        val tmpFileName = "${System.currentTimeMillis()}_${System.nanoTime()}$TMP_SUFFIX"
        val mimeType = getMimeType(finalFileName)
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // Step 1: Insert .tmp file with IS_PENDING=1
        val tmpValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, tmpFileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val tmpUri = try {
            context.contentResolver.insert(collection, tmpValues)
        } catch (e: Exception) {
            return WriteResult(
                success = false,
                outputPath = null,
                error = "Failed to create tmp file in Music: ${e.message}"
            )
        }

        if (tmpUri == null) {
            return WriteResult(
                success = false,
                outputPath = null,
                error = "Failed to insert tmp file into MediaStore"
            )
        }

        // Step 2: Write content
        try {
            context.contentResolver.openOutputStream(tmpUri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Cannot open output stream")
        } catch (e: Exception) {
            safeDelete(tmpUri)
            return WriteResult(
                success = false,
                outputPath = null,
                error = "Failed to write content: ${e.message}"
            )
        }

        // Step 3: Rename to final name and clear IS_PENDING
        val finalValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, finalFileName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
        }

        try {
            val updated = context.contentResolver.update(tmpUri, finalValues, null, null)
            if (updated == 0) {
                throw Exception("Update returned 0 rows affected")
            }
        } catch (e: Exception) {
            safeDelete(tmpUri)
            return WriteResult(
                success = false,
                outputPath = null,
                error = "Failed to rename tmp to final: ${e.message}"
            )
        }

        // Step 4: 验证最终文件名是否正确
        val actualFileName = queryDisplayName(tmpUri)
        if (actualFileName != null && actualFileName != finalFileName) {
            android.util.Log.w("OutputWriter", 
                "MediaStore renamed file: expected=$finalFileName, actual=$actualFileName")
            return WriteResult(
                success = true,
                outputPath = "${Environment.DIRECTORY_MUSIC}/$actualFileName",
                error = null
            )
        }

        android.util.Log.d("OutputWriter", "createNewFile: success")
        return WriteResult(
            success = true,
            outputPath = "${Environment.DIRECTORY_MUSIC}/$finalFileName",
            error = null
        )
    }

    /**
     * 查询 Uri 对应的 DISPLAY_NAME
     */
    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
            } else {
                null
            }
        }
    }

    /**
     * 安全删除 Uri
     */
    private fun safeDelete(uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {}
    }

    /**
     * 删除 Music 目录中的指定文件
     *
     * 用于覆盖模式下先删除旧文件
     *
     * 【重要】使用"地毯式"删除策略：
     * 1. 通过 selection 条件删除 Music 目录下所有同名文件的 MediaStore 记录
     * 2. 这可以清理可能存在的冗余索引，确保物理文件被正确删除
     * 3. 如果 MediaStore 删除后物理文件仍存在，先扫描再删除
     *
     * @param fileName 要删除的文件名
     * @return true 如果删除成功或文件不存在
     */
    suspend fun deleteFromMusic(fileName: String): Boolean = withContext(Dispatchers.IO) {
        android.util.Log.d("OutputWriter", "deleteFromMusic: attempting to delete '$fileName'")
        
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        
        // Step 1: 地毯式删除 - 通过 selection 删除所有同名记录
        // 使用 LIKE 匹配 Music 目录（兼容 "Music", "Music/", 空字符串等）
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)
        
        try {
            val deletedCount = context.contentResolver.delete(collection, selection, selectionArgs)
            android.util.Log.d("OutputWriter", 
                "deleteFromMusic: bulk delete by selection, deletedCount=$deletedCount")
            
            // 如果删除了至少一条记录，检查物理文件是否还存在
            if (deletedCount > 0) {
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val file = File(musicDir, fileName)
                if (file.exists()) {
                    android.util.Log.w("OutputWriter", 
                        "deleteFromMusic: MediaStore records deleted but physical file still exists!")
                    // 物理文件仍存在，可能需要额外处理
                    // 尝试再次扫描并删除
                    val scannedUri = scanFileSyncAndGetUri(file.absolutePath)
                    if (scannedUri != null) {
                        val deleted2 = context.contentResolver.delete(scannedUri, null, null)
                        android.util.Log.d("OutputWriter", 
                            "deleteFromMusic: second delete attempt result=$deleted2")
                    }
                }
                return@withContext true
            }
        } catch (e: Exception) {
            android.util.Log.e("OutputWriter", "deleteFromMusic: bulk delete failed", e)
        }
        
        // Step 2: 如果 MediaStore 没有记录，检查文件系统
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val file = File(musicDir, fileName)
        
        if (!file.exists()) {
            // 文件确实不存在
            android.util.Log.d("OutputWriter", "deleteFromMusic: file does not exist, returning true")
            return@withContext true
        }
        
        android.util.Log.w("OutputWriter", 
            "deleteFromMusic: MediaStore has no record but file exists at '${file.absolutePath}'")
        
        // Step 3: 强制扫描文件，直接获取 Uri 并删除
        val scannedUri = scanFileSyncAndGetUri(file.absolutePath)
        android.util.Log.d("OutputWriter", "deleteFromMusic: scanFile returned uri=$scannedUri")
        
        if (scannedUri != null) {
            try {
                val deleted = context.contentResolver.delete(scannedUri, null, null)
                android.util.Log.d("OutputWriter", 
                    "deleteFromMusic: delete scanned uri result=$deleted")
                
                // 再次验证物理文件是否还存在
                if (file.exists()) {
                    android.util.Log.w("OutputWriter", 
                        "deleteFromMusic: file still exists after delete, trying File.delete()")
                    val directDeleted = file.delete()
                    android.util.Log.d("OutputWriter", 
                        "deleteFromMusic: File.delete() result=$directDeleted")
                    return@withContext directDeleted
                }
                return@withContext deleted > 0
            } catch (e: Exception) {
                android.util.Log.e("OutputWriter", "deleteFromMusic: delete scanned uri failed", e)
            }
        }
        
        // Step 4: 最后尝试直接通过文件系统删除
        android.util.Log.w("OutputWriter", 
            "deleteFromMusic: all MediaStore methods failed, trying File.delete() as last resort")
        val directDeleted = try {
            file.delete()
        } catch (e: Exception) {
            android.util.Log.e("OutputWriter", "File.delete() failed", e)
            false
        }
        android.util.Log.d("OutputWriter", "deleteFromMusic: File.delete() result=$directDeleted")
        directDeleted
    }

    /**
     * 同步扫描文件，返回扫描得到的 Uri
     *
     * 使用 CountDownLatch 等待扫描完成
     *
     * @param filePath 文件绝对路径
     * @return 扫描成功返回 Uri，否则返回 null
     */
    private fun scanFileSyncAndGetUri(filePath: String): Uri? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var scannedUri: Uri? = null
        
        android.util.Log.d("OutputWriter", "scanFileSyncAndGetUri: scanning '$filePath'")
        
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(filePath),
            null
        ) { path, uri ->
            android.util.Log.d("OutputWriter", "scanFileSyncAndGetUri: callback path=$path, uri=$uri")
            scannedUri = uri
            latch.countDown()
        }
        
        return try {
            // 等待最多 5 秒
            val completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            android.util.Log.d("OutputWriter", 
                "scanFileSyncAndGetUri: completed=$completed, scannedUri=$scannedUri")
            if (completed) scannedUri else null
        } catch (e: InterruptedException) {
            android.util.Log.e("OutputWriter", "scanFileSyncAndGetUri: interrupted", e)
            null
        }
    }

    /**
     * 根据文件扩展名获取 MIME 类型
     */
    private fun getMimeType(fileName: String): String {
        return when {
            fileName.lowercase().endsWith(".flac") -> "audio/flac"
            fileName.lowercase().endsWith(".mp3") -> "audio/mpeg"
            else -> "audio/*"
        }
    }
}
