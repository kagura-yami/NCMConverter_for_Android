package com.example.ncmconverter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ncmconverter.R
import com.example.ncmconverter.domain.model.NcmFileRef

/**
 * Main Screen - 主界面
 *
 * 职责 (per §3.1):
 * - 显示扫描到的 NCM 文件列表
 * - 转换状态
 * - 一键转换按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainViewModel.MainUiState,
    settings: com.example.ncmconverter.domain.model.AppSettings,
    convertSummary: MainViewModel.ConvertSummary?,
    onSelectDirectory: () -> Unit,
    onStartScan: () -> Unit,
    onStartConvert: () -> Unit,
    onCancelConvert: () -> Unit,
    onToggleSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onClearError: () -> Unit,
    onClearSummary: () -> Unit,
    onUpdateConflictPolicy: (com.example.ncmconverter.domain.model.ConflictPolicy) -> Unit,
    onUpdateAutoDelete: (Boolean) -> Unit,
    onUpdateDebugMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold
                    ) 
                },
                actions = {
                    IconButton(onClick = onStartScan, enabled = uiState.hasPermission && !uiState.isConverting) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "历史记录")
                    }
                    IconButton(onClick = onToggleSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!uiState.hasPermission) {
                // 未授权界面
                PermissionRequestContent(
                    onSelectDirectory = onSelectDirectory,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 主内容
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 文件列表
                    if (uiState.isScanning) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(stringResource(R.string.scanning))
                            }
                        }
                    } else if (uiState.ncmFiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.no_files_found),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.ncmFiles) { file ->
                                NcmFileItem(file = file)
                            }
                        }
                    }

                    // 底部控制区
                    BottomControlBar(
                        fileCount = uiState.ncmFiles.size,
                        isConverting = uiState.isConverting,
                        progress = uiState.convertProgress,
                        onStartConvert = onStartConvert,
                        onCancelConvert = onCancelConvert
                    )
                }
            }

            // 设置弹窗
            if (uiState.showSettings) {
                SettingsDialog(
                    settings = settings,
                    onDismiss = onToggleSettings,
                    onUpdateConflictPolicy = onUpdateConflictPolicy,
                    onUpdateAutoDelete = onUpdateAutoDelete,
                    onUpdateDebugMode = onUpdateDebugMode
                )
            }

            // 错误提示
            AnimatedVisibility(
                visible = uiState.lastError != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = onClearError) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                ) {
                    Text(uiState.lastError ?: "")
                }
            }

            // 转换完成提示
            if (convertSummary != null) {
                AlertDialog(
                    onDismissRequest = onClearSummary,
                    title = { Text(stringResource(R.string.convert_complete_title)) },
                    text = {
                        Column {
                            Text("✅ " + stringResource(R.string.result_success, convertSummary.successCount))
                            if (convertSummary.warningCount > 0) {
                                Text("⚠️ " + stringResource(R.string.result_warning, convertSummary.warningCount))
                            }
                            if (convertSummary.skippedCount > 0) {
                                Text("⏭️ " + stringResource(R.string.result_skipped, convertSummary.skippedCount))
                            }
                            if (convertSummary.failedCount > 0) {
                                Text("❌ " + stringResource(R.string.result_failed, convertSummary.failedCount))
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = onClearSummary) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }
        }
    }
}

/**
 * 权限请求内容
 */
@Composable
private fun PermissionRequestContent(
    onSelectDirectory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            stringResource(R.string.permission_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onSelectDirectory,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.permission_button))
        }
    }
}

/**
 * NCM 文件列表项
 */
@Composable
private fun NcmFileItem(
    file: NcmFileRef,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName.removeSuffix(".ncm"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 底部控制栏
 */
@Composable
private fun BottomControlBar(
    fileCount: Int,
    isConverting: Boolean,
    progress: MainViewModel.ConvertProgress?,
    onStartConvert: () -> Unit,
    onCancelConvert: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (isConverting && progress != null) {
                // 转换进度
                Text(
                    text = stringResource(R.string.converting_progress, progress.current, progress.total),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = progress.currentFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { progress.current.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "✅ ${progress.successCount}  ❌ ${progress.failedCount}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    
                    TextButton(onClick = onCancelConvert) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else {
                // 转换按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.files_ready, fileCount),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Button(
                        onClick = onStartConvert,
                        enabled = fileCount > 0
                    ) {
                        Text(stringResource(R.string.convert_all))
                    }
                }
            }
        }
    }
}

/**
 * 格式化文件大小
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
