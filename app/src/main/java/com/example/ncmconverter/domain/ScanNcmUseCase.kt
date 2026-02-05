package com.example.ncmconverter.domain

import com.example.ncmconverter.domain.model.NcmFileRef
import com.example.ncmconverter.infra.SAFManager
import kotlinx.coroutines.flow.Flow

/**
 * Scan NCM UseCase - 流式扫描 NCM 文件
 *
 * 职责 (per §3.2):
 * - 流式扫描 NCM 文件
 * - 返回 Flow<NcmFileRef>
 *
 * 扫描策略 (per §6.1):
 * - 流式扫描：使用 DocumentsContract.queryChildDocuments() + Cursor 分页
 * - 不一次性加载：禁止 DocumentFile.fromTreeUri().listFiles() 全量加载
 * - 按需打开流：每个文件仅在需要时 openInputStream，用完立即关闭
 * - 仅保留引用：Cursor 遍历时仅保留 documentId，不持有 DocumentFile 对象
 */
class ScanNcmUseCase(
    private val safManager: SAFManager
) {

    /**
     * 检查是否已授权 SAF 访问
     *
     * @return true 如果有有效的持久化权限
     */
    fun hasPermission(): Boolean = safManager.hasValidPermission()

    /**
     * 执行流式扫描
     *
     * 返回 Flow<NcmFileRef>，每发现一个 .ncm 文件即发射一次
     * 调用方可通过 Flow 操作符进行过滤、收集等操作
     *
     * @return Flow<NcmFileRef> 流式返回扫描到的 NCM 文件引用
     * @throws IllegalStateException 如果未授权
     * @throws SecurityException 如果权限丢失
     */
    fun scan(): Flow<NcmFileRef> = safManager.scanNcmFiles()

    /**
     * 扫描并收集为列表
     *
     * 便捷方法，将 Flow 收集为 List
     * 适用于需要一次性获取所有文件的场景（如 UI 显示列表）
     *
     * @return List<NcmFileRef> 所有扫描到的 NCM 文件引用
     */
    suspend fun scanToList(): List<NcmFileRef> {
        val result = mutableListOf<NcmFileRef>()
        scan().collect { result.add(it) }
        return result
    }
}
