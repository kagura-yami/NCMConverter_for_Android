package com.example.ncmconverter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ncmconverter.data.entity.HistoryEntity
import com.example.ncmconverter.infra.HistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 历史记录 ViewModel
 */
class HistoryViewModel(
    private val historyRepository: HistoryRepository
) : ViewModel() {

    private val _historyList = MutableStateFlow<List<HistoryEntity>>(emptyList())
    val historyList: StateFlow<List<HistoryEntity>> = _historyList.asStateFlow()

    private val _selectedHistory = MutableStateFlow<HistoryEntity?>(null)
    val selectedHistory: StateFlow<HistoryEntity?> = _selectedHistory.asStateFlow()

    init {
        viewModelScope.launch {
            historyRepository.historyFlow.collect { list ->
                _historyList.value = list
            }
        }
    }

    /**
     * 选择一条历史记录（显示详情）
     */
    fun selectHistory(history: HistoryEntity) {
        _selectedHistory.value = history
    }

    /**
     * 关闭详情
     */
    fun clearSelection() {
        _selectedHistory.value = null
    }

    /**
     * 清空所有历史
     */
    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearAll()
        }
    }
}
