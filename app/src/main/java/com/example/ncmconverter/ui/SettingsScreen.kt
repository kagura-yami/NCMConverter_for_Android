package com.example.ncmconverter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ncmconverter.R
import com.example.ncmconverter.domain.model.AppSettings
import com.example.ncmconverter.domain.model.ConflictPolicy

/**
 * Settings Screen - 设置界面
 *
 * 职责 (per §3.1):
 * - 冲突策略选择
 * - 是否自动删除原文件
 *
 * 实现为 AlertDialog，嵌入 MainScreen
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdateConflictPolicy: (ConflictPolicy) -> Unit,
    onUpdateAutoDelete: (Boolean) -> Unit,
    onUpdateDebugMode: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // 冲突策略
        Text(
            text = stringResource(R.string.conflict_policy_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.conflict_policy_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(Modifier.selectableGroup()) {
            ConflictPolicyOption(
                text = stringResource(R.string.conflict_skip),
                description = stringResource(R.string.conflict_skip_desc),
                selected = settings.conflictPolicy == ConflictPolicy.SKIP,
                onClick = { onUpdateConflictPolicy(ConflictPolicy.SKIP) }
            )
            ConflictPolicyOption(
                text = stringResource(R.string.conflict_overwrite),
                description = stringResource(R.string.conflict_overwrite_desc),
                selected = settings.conflictPolicy == ConflictPolicy.OVERWRITE,
                onClick = { onUpdateConflictPolicy(ConflictPolicy.OVERWRITE) }
            )
            ConflictPolicyOption(
                text = stringResource(R.string.conflict_rename),
                description = stringResource(R.string.conflict_rename_desc),
                selected = settings.conflictPolicy == ConflictPolicy.RENAME,
                onClick = { onUpdateConflictPolicy(ConflictPolicy.RENAME) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))

        // 源文件处理
        Text(
            text = stringResource(R.string.source_file_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onUpdateAutoDelete(!settings.autoDeleteSource) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.auto_delete_source),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.auto_delete_source_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = settings.autoDeleteSource,
                onCheckedChange = onUpdateAutoDelete
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))

        // 调试模式
        Text(
            text = "调试模式",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onUpdateDebugMode(!settings.debugMode) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "显示调试信息",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "显示更多技术细节（真实路径、冲突处理结果、回退原因等）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = settings.debugMode,
                onCheckedChange = onUpdateDebugMode
            )
        }
    }
}

/**
 * 冲突策略选项
 */
@Composable
private fun ConflictPolicyOption(
    text: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null // handled by Row
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Settings Dialog - 设置弹窗
 *
 * 包装 SettingsScreen 为 AlertDialog
 */
@Composable
fun SettingsDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onUpdateConflictPolicy: (ConflictPolicy) -> Unit,
    onUpdateAutoDelete: (Boolean) -> Unit,
    onUpdateDebugMode: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            SettingsScreen(
                settings = settings,
                onUpdateConflictPolicy = onUpdateConflictPolicy,
                onUpdateAutoDelete = onUpdateAutoDelete,
                onUpdateDebugMode = onUpdateDebugMode
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        }
    )
}
