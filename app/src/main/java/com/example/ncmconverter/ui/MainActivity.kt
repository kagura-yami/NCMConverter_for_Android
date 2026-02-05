package com.example.ncmconverter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ncmconverter.NcmConverterApp
import com.example.ncmconverter.domain.ConvertUseCase
import com.example.ncmconverter.domain.ScanNcmUseCase
import com.example.ncmconverter.infra.*
import com.example.ncmconverter.ui.theme.NCMConverterTheme

/**
 * Main Activity - 主入口
 *
 * 职责 (per §3.1):
 * - Host Compose UI
 * - Handle SAF authorization results
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化依赖
        // 注意：生产环境应使用依赖注入框架
        val safManager = SAFManager(applicationContext)
        val fileCopier = FileCopier(applicationContext, safManager)
        val ncmDecoder = NcmDecoder(applicationContext)
        val tagWriter = TagWriter()
        val outputWriter = OutputWriter(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)
        val historyRepository = HistoryRepository(applicationContext)

        val scanNcmUseCase = ScanNcmUseCase(safManager)
        val convertUseCase = ConvertUseCase(
            safManager = safManager,
            fileCopier = fileCopier,
            ncmDecoder = ncmDecoder,
            tagWriter = tagWriter,
            outputWriter = outputWriter,
            settingsRepository = settingsRepository,
            historyRepository = historyRepository
        )

        viewModel = MainViewModel(
            safManager = safManager,
            scanNcmUseCase = scanNcmUseCase,
            convertUseCase = convertUseCase,
            settingsRepository = settingsRepository
        )

        setContent {
            NCMConverterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsState()
                    val settings by viewModel.settings.collectAsState()
                    val convertSummary by viewModel.convertSummary.collectAsState()
                    
                    // 简单导航状态
                    var showHistory by androidx.compose.runtime.remember { 
                        androidx.compose.runtime.mutableStateOf(false) 
                    }
                    
                    // History ViewModel
                    val historyViewModel = androidx.compose.runtime.remember {
                        HistoryViewModel(historyRepository)
                    }
                    val historyList by historyViewModel.historyList.collectAsState()
                    val selectedHistory by historyViewModel.selectedHistory.collectAsState()

                    // SAF directory picker launcher
                    val directoryPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        viewModel.onDirectorySelected(uri)
                    }

                    if (showHistory) {
                        HistoryScreen(
                            historyList = historyList,
                            selectedHistory = selectedHistory,
                            debugMode = settings.debugMode,
                            onSelectHistory = historyViewModel::selectHistory,
                            onClearSelection = historyViewModel::clearSelection,
                            onBack = { showHistory = false }
                        )
                    } else {
                        MainScreen(
                            uiState = uiState,
                            settings = settings,
                            convertSummary = convertSummary,
                            onSelectDirectory = {
                                directoryPickerLauncher.launch(null)
                            },
                            onStartScan = viewModel::startScan,
                            onStartConvert = viewModel::startConvert,
                            onCancelConvert = viewModel::cancelConvert,
                            onToggleSettings = viewModel::toggleSettings,
                            onOpenHistory = { showHistory = true },
                            onClearError = viewModel::clearError,
                            onClearSummary = viewModel::clearConvertSummary,
                            onUpdateConflictPolicy = viewModel::updateConflictPolicy,
                            onUpdateAutoDelete = viewModel::updateAutoDelete,
                            onUpdateDebugMode = viewModel::updateDebugMode
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 检查权限状态（可能在外部被撤销）
        viewModel.checkPermission()
    }
}
