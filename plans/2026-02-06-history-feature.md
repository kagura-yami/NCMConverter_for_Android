# V1 历史记录功能实施计划

## 1. 功能定位

历史记录 = 转换结果日志（工具型，非娱乐型）

**目标**：
- 用户知道刚才转了什么
- 能看出成功 / 跳过 / 失败 / 覆盖回退
- 能找到生成文件的位置

**不做**：播放、编辑、批量操作、重试

---

## 2. 数据模型

### HistoryStatus
```kotlin
enum class HistoryStatus {
    SUCCESS,              // ✓ 转换完成
    SUCCESS_WITH_WARNING, // ⚠ 转换完成（有警告）
    SKIPPED,              // ⏭ 文件已存在，已跳过
    FAILED,               // ✗ 转换失败
    OVERWRITE_FALLBACK    // ⚠ 无法覆盖，已保存为新文件
}
```

### HistoryItem
```kotlin
@Entity(tableName = "history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceName: String,           // 原文件名 (xxx.ncm)
    val outputName: String?,          // 输出文件名 (xxx.flac)
    val outputPath: String?,          // 输出路径 (Music/xxx.flac)
    val status: HistoryStatus,
    val message: String,              // 用户可读文案
    val conflictPolicy: String,       // 使用的冲突策略
    val timestamp: Long               // 时间戳
)
```

---

## 3. 实施步骤

### Step 1: 添加 Room 依赖
- [ ] build.gradle.kts 添加 Room 依赖

### Step 2: 数据层
- [ ] HistoryStatus enum
- [ ] HistoryItem entity
- [ ] HistoryDao
- [ ] HistoryDatabase
- [ ] HistoryRepository

### Step 3: Domain 层集成
- [ ] 修改 ConvertUseCase，在每个文件转换完成后记录历史
- [ ] 处理"覆盖回退"的特殊状态

### Step 4: UI 层
- [ ] HistoryViewModel
- [ ] HistoryScreen（列表页）
- [ ] HistoryDetailSheet（底部详情弹窗）
- [ ] 主界面右上角添加"历史"入口

### Step 5: 数据清理
- [ ] 限制最多 200 条记录（按时间保留最新）

---

## 4. 文案规范

| 状态 | 图标 | 文案 |
|------|------|------|
| SUCCESS | ✓ | 转换完成 |
| SUCCESS_WITH_WARNING | ⚠ | 转换完成（有警告: {warning}） |
| SKIPPED | ⏭ | 文件已存在，已跳过 |
| FAILED | ✗ | 转换失败：{error} |
| OVERWRITE_FALLBACK | ⚠ | 无法覆盖，已保存为新文件 |

---

## 5. UI 规范

### 列表项
```
[状态图标] 原文件名.ncm
          结果描述
          输出文件名.flac（成功时）
          03:25
```

### 详情 Bottom Sheet
```
┌─────────────────────────────┐
│ 转换详情                    │
├─────────────────────────────┤
│ 原文件：xxx.ncm             │
│ 结果：转换完成              │
│ 输出文件：xxx.flac          │
│ 输出目录：Music             │
│ 冲突策略：覆盖              │
│ 时间：2026-02-06 03:25      │
├─────────────────────────────┤
│ [打开文件位置]    [关闭]    │
└─────────────────────────────┘
```

---

## 6. 验证清单

- [ ] 转换成功 → 历史记录正确
- [ ] 跳过 → 历史记录正确
- [ ] 失败 → 历史记录正确
- [ ] 覆盖回退 → 历史记录显示特殊状态
- [ ] 历史列表按时间倒序
- [ ] 点击打开详情
- [ ] 打开文件位置有效
- [ ] 超过 200 条自动清理
