package com.example.ncmconverter.infra

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.example.ncmconverter.domain.model.NcmFileRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * SAF Manager - 处理 Storage Access Framework 相关操作
 *
 * 职责 (per §3.3):
 * - 请求 ACTION_OPEN_DOCUMENT_TREE
 * - 持久化 URI 权限
 * - 流式枚举 .ncm 文件
 *
 * 约束:
 * - 使用 DocumentsContract.queryChildDocuments() + Cursor 分页 (§6.1)
 * - 禁止 DocumentFile.fromTreeUri().listFiles() 全量加载 (§6.1)
 * - 仅保留 documentId，不持有 DocumentFile 对象 (§6.1)
 * - 不缓存 Uri 对象
 */
class SAFManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "saf_prefs"
        private const val KEY_TREE_URI = "tree_uri"

        // Projection for querying documents
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
    }

    /**
     * 创建目录选择 Intent
     * 
     * UI 层通过 ActivityResultLauncher 调用此 Intent
     */
    fun createOpenDocumentTreeIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    /**
     * 持久化 URI 权限并保存
     *
     * @param treeUri 从 ACTION_OPEN_DOCUMENT_TREE 返回的 Uri
     */
    fun persistTreeUri(treeUri: Uri) {
        // Take persistable permission
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)

        // Save to preferences
        prefs.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }

    /**
     * 获取已保存的 Tree URI
     *
     * @return 已持久化的 Uri，如果不存在则返回 null
     */
    fun getSavedTreeUri(): Uri? {
        val uriString = prefs.getString(KEY_TREE_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    /**
     * 检查是否有有效的持久化权限
     */
    fun hasValidPermission(): Boolean {
        val treeUri = getSavedTreeUri() ?: return false
        val persistedUris = context.contentResolver.persistedUriPermissions
        return persistedUris.any { 
            it.uri == treeUri && it.isReadPermission && it.isWritePermission 
        }
    }

    /**
     * 释放持久化权限并清除保存的 URI
     */
    fun releasePermission() {
        val treeUri = getSavedTreeUri() ?: return
        try {
            val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                              Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(treeUri, releaseFlags)
        } catch (e: SecurityException) {
            // Permission might already be released
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    /**
     * 流式扫描 .ncm 文件
     *
     * 使用 Cursor 分页，每次仅发射一个 NcmFileRef
     * 不一次性加载所有文件 (§6.1)
     *
     * @return Flow<NcmFileRef> 流式返回扫描到的 NCM 文件
     * @throws SecurityException 如果权限丢失
     */
    fun scanNcmFiles(): Flow<NcmFileRef> = flow {
        val treeUri = getSavedTreeUri()
            ?: throw IllegalStateException("No tree URI saved")

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )

        val cursor: Cursor? = context.contentResolver.query(
            childrenUri,
            PROJECTION,
            null,
            null,
            null
        )

        cursor?.use { c ->
            val idIndex = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

            while (c.moveToNext()) {
                val displayName = c.getString(nameIndex) ?: continue
                
                // Only emit .ncm files
                if (!displayName.lowercase().endsWith(".ncm")) {
                    continue
                }

                val documentId = c.getString(idIndex) ?: continue
                val size = c.getLong(sizeIndex)

                emit(NcmFileRef(
                    documentId = documentId,
                    displayName = displayName,
                    size = size
                ))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 根据 documentId 构建临时 Uri
     *
     * 【重要】此方法仅供 FileCopier 使用，Domain 层不得直接调用
     * Uri 不缓存，每次临时构造
     *
     * @param documentId 文档 ID
     * @return 对应的文档 Uri
     */
    fun buildDocumentUri(documentId: String): Uri {
        val treeUri = getSavedTreeUri()
            ?: throw IllegalStateException("No tree URI saved")
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    /**
     * 打开文档输入流
     *
     * 【重要】调用方负责关闭流
     *
     * @param documentId 文档 ID
     * @return InputStream
     */
    fun openInputStream(documentId: String) = 
        context.contentResolver.openInputStream(buildDocumentUri(documentId))

    /**
     * 删除原始 NCM 文件
     *
     * @param documentId 要删除的文档 ID
     * @return true 如果删除成功，false 如果删除失败
     */
    fun deleteDocument(documentId: String): Boolean {
        return try {
            val uri = buildDocumentUri(documentId)
            android.util.Log.d("SAFManager", "deleteDocument: attempting to delete documentId=$documentId, uri=$uri")
            val result = DocumentsContract.deleteDocument(context.contentResolver, uri)
            android.util.Log.d("SAFManager", "deleteDocument: result=$result")
            result
        } catch (e: Exception) {
            android.util.Log.e("SAFManager", "deleteDocument: failed to delete documentId=$documentId", e)
            false
        }
    }
}
