package com.example.ncmconverter.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ncmconverter.domain.ConvertUseCase
import com.example.ncmconverter.domain.ScanNcmUseCase
import com.example.ncmconverter.domain.model.AppSettings
import com.example.ncmconverter.domain.model.ConvertResult
import com.example.ncmconverter.domain.model.NcmFileRef
import com.example.ncmconverter.infra.SAFManager
import com.example.ncmconverter.infra.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Main ViewModel - UI 状态管理
 *
 * 职责 (per §3.1):
 * - 状态管理
 * - 订阅 Domain 层 StateFlow
 * - **不包含任何业务判断**
 *
 * 所有业务逻辑由 Domain 层 UseCase 处理
 */
class MainViewModel(
    private val safManager: SAFManager,
    private val scanNcmUseCase: ScanNcmUseCase,
    private val convertUseCase: ConvertUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // ==================== UI State ====================

    /**
     * 主界面 UI 状态
     */
    data class MainUiState(
        val hasPermission: Boolean = false,
        val isScanning: Boolean = false,
        val isConverting: Boolean = false,
        val ncmFiles: List<NcmFileRef> = emptyList(),
        val convertProgress: ConvertProgress? = null,
        val lastError: String? = null,
        val showSettings: Boolean = false
    )

    /**
     * 转换进度
     */
    data class ConvertProgress(
        val current: Int,
        val total: Int,
        val currentFileName: String,
        val successCount: Int,
        val failedCount: Int
    )

    /**
     * 转换完成结果
     */
    data class ConvertSummary(
        val successCount: Int,
        val warningCount: Int,
        val failedCount: Int,
        val skippedCount: Int
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings(
        conflictPolicy = com.example.ncmconverter.domain.model.ConflictPolicy.SKIP,
        autoDeleteSource = false
    ))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _convertSummary = MutableStateFlow<ConvertSummary?>(null)
    val convertSummary: StateFlow<ConvertSummary?> = _convertSummary.asStateFlow()

    private var convertJob: Job? = null

    init {
        // 订阅设置变化
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _settings.value = settings
            }
        }

        // 检查初始权限状态
        checkPermission()
    }

    // ==================== Permission ====================

    /**
     * 检查 SAF 权限
     */
    fun checkPermission() {
        _uiState.update { it.copy(hasPermission = safManager.hasValidPermission()) }
    }

    /**
     * 获取目录选择 Intent
     */
    fun getOpenDocumentTreeIntent() = safManager.createOpenDocumentTreeIntent()

    /**
     * 处理目录选择结果
     */
    fun onDirectorySelected(uri: Uri?) {
        if (uri != null) {
            safManager.persistTreeUri(uri)
            _uiState.update { it.copy(hasPermission = true, lastError = null) }
            // 自动开始扫描
            startScan()
        }
    }

    /**
     * 释放权限
     */
    fun releasePermission() {
        safManager.releasePermission()
        _uiState.update { 
            it.copy(
                hasPermission = false, 
                ncmFiles = emptyList()
            ) 
        }
    }

    // ==================== Scan ====================

    /**
     * 开始扫描
     */
    fun startScan() {
        if (_uiState.value.isScanning || _uiState.value.isConverting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, lastError = null) }

            try {
                val files = scanNcmUseCase.scanToList()
                _uiState.update { 
                    it.copy(
                        isScanning = false, 
                        ncmFiles = files
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isScanning = false, 
                        lastError = "Scan failed: ${e.message}"
                    ) 
                }
            }
        }
    }

    // ==================== Convert ====================

    /**
     * 开始转换
     */
    fun startConvert() {
        val files = _uiState.value.ncmFiles
        if (files.isEmpty() || _uiState.value.isConverting) return

        convertJob = viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    isConverting = true, 
                    lastError = null,
                    convertProgress = ConvertProgress(0, files.size, "", 0, 0)
                ) 
            }
            _convertSummary.value = null

            var successCount = 0
            var warningCount = 0
            var failedCount = 0
            var skippedCount = 0

            try {
                convertUseCase.convertBatch(files).collect { progress ->
                    // 更新进度
                    when (progress.result) {
                        is ConvertResult.Success -> successCount++
                        is ConvertResult.SuccessWithWarning -> warningCount++
                        is ConvertResult.OverwriteFallback -> warningCount++  // 算作 warning
                        is ConvertResult.Skipped -> skippedCount++
                        is ConvertResult.Failed -> failedCount++
                    }

                    _uiState.update {
                        it.copy(
                            convertProgress = ConvertProgress(
                                current = progress.current + 1,
                                total = progress.total,
                                currentFileName = progress.currentFile.displayName,
                                successCount = successCount + warningCount,
                                failedCount = failedCount
                            )
                        )
                    }
                }

                // 转换完成
                _convertSummary.value = ConvertSummary(
                    successCount = successCount,
                    warningCount = warningCount,
                    failedCount = failedCount,
                    skippedCount = skippedCount
                )

            } catch (e: CancellationException) {
                // 用户取消
                convertUseCase.clearAllCache()
                _convertSummary.value = ConvertSummary(
                    successCount = successCount,
                    warningCount = warningCount,
                    failedCount = failedCount,
                    skippedCount = skippedCount
                )
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(lastError = "Convert failed: ${e.message}") 
                }
            } finally {
                _uiState.update { 
                    it.copy(
                        isConverting = false, 
                        convertProgress = null
                    ) 
                }
                // 重新扫描以更新列表
                startScan()
            }
        }
    }

    /**
     * 取消转换
     */
    fun cancelConvert() {
        convertJob?.cancel()
        convertJob = null
    }

    /**
     * 清除转换结果摘要
     */
    fun clearConvertSummary() {
        _convertSummary.value = null
    }

    // ==================== Settings ====================

    /**
     * 显示/隐藏设置界面
     */
    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    /**
     * 更新冲突策略
     */
    fun updateConflictPolicy(policy: com.example.ncmconverter.domain.model.ConflictPolicy) {
        viewModelScope.launch {
            settingsRepository.setConflictPolicy(policy)
        }
    }

    /**
     * 更新自动删除设置
     */
    fun updateAutoDelete(autoDelete: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoDeleteSource(autoDelete)
        }
    }

    /**
     * 更新调试模式设置
     */
    fun updateDebugMode(debugMode: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDebugMode(debugMode)
        }
    }

    /**
     * 清除错误
     */
    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }
}
