package com.example.ncmconverter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ncmconverter.data.entity.HistoryEntity
import com.example.ncmconverter.domain.model.HistoryStatus
import java.text.SimpleDateFormat
import java.util.*

/**
 * 历史记录界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyList: List<HistoryEntity>,
    selectedHistory: HistoryEntity?,
    debugMode: Boolean,
    onSelectHistory: (HistoryEntity) -> Unit,
    onClearSelection: () -> Unit,
    onBack: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (historyList.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无历史记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(historyList) { history ->
                    HistoryListItem(
                        history = history,
                        onClick = { onSelectHistory(history) }
                    )
                }
            }
        }
    }
    
    // 详情 Bottom Sheet
    if (selectedHistory != null) {
        ModalBottomSheet(
            onDismissRequest = onClearSelection,
            sheetState = sheetState
        ) {
            HistoryDetailSheet(
                history = selectedHistory,
                debugMode = debugMode,
                onDismiss = onClearSelection
            )
        }
    }
}

/**
 * 历史记录列表项
 */
@Composable
private fun HistoryListItem(
    history: HistoryEntity,
    onClick: () -> Unit
) {
    val (icon, iconColor) = when (history.status) {
        HistoryStatus.SUCCESS -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        HistoryStatus.SUCCESS_WITH_WARNING -> Icons.Default.Warning to Color(0xFFFF9800)
        HistoryStatus.SKIPPED -> Icons.Default.SkipNext to Color(0xFF9E9E9E)
        HistoryStatus.OVERWRITE_FALLBACK -> Icons.Default.Warning to Color(0xFFFF9800)
        HistoryStatus.FAILED -> Icons.Default.Error to Color(0xFFF44336)
    }
    
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeStr = dateFormat.format(Date(history.timestamp))
    
    ListItem(
        headlineContent = {
            Text(
                history.sourceName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Column {
                Text(
                    history.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (history.outputName != null) {
                    Text(
                        "→ ${history.outputName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = iconColor)
        },
        trailingContent = {
            Text(
                timeStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
    HorizontalDivider()
}

/**
 * 历史记录详情 Bottom Sheet
 */
@Composable
private fun HistoryDetailSheet(
    history: HistoryEntity,
    debugMode: Boolean,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = dateFormat.format(Date(history.timestamp))
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            "转换详情",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        DetailRow("原文件", history.sourceName)
        DetailRow("结果", history.message)
        if (history.outputName != null) {
            DetailRow("输出文件", history.outputName)
        }
        if (history.outputPath != null) {
            DetailRow("输出目录", history.outputPath.substringBeforeLast("/"))
        }
        DetailRow("冲突策略", translatePolicy(history.conflictPolicy))
        DetailRow("时间", timeStr)
        
        // 调试模式显示更多信息
        if (debugMode) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                "调试信息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 完整路径
            if (history.outputPath != null) {
                DetailRow("完整路径", history.outputPath)
            }
            
            // 状态编码
            DetailRow("状态编码", history.status.name)
            
            // 回退说明
            if (history.status == HistoryStatus.OVERWRITE_FALLBACK) {
                DetailRow("回退原因", "由于 Android 分区存储限制，无法覆盖非本应用创建的文件，已保存为新文件")
            }
            
            // 时间戳
            DetailRow("时间戳", history.timestamp.toString())
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private fun translatePolicy(policy: String): String {
    return when (policy) {
        "SKIP" -> "跳过"
        "OVERWRITE" -> "覆盖"
        "RENAME" -> "重命名"
        else -> policy
    }
}
