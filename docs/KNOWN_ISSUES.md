# V1 已知限制 (Known Limitations)

本文档记录 V1 版本的已知限制和设计边界，供开发者和测试者参考。

---

## 1. 覆盖策略回退

### 现象
选择"覆盖"策略时，部分情况下会生成 `歌曲名(1).flac` 而非覆盖原文件。

### 原因
Android 10+ 分区存储 (Scoped Storage) 限制：
- 应用只能覆盖 **自己创建的文件**
- 对于其他应用创建的同名文件，`ContentResolver.openOutputStream()` 会抛出 `SecurityException`

### 代码位置
- `OutputWriter.kt` → `overwriteExistingFile()` → 捕获 `SecurityException`
- `OutputWriter.kt` → `writeToMusic()` → 检测 `PERMISSION_DENIED` → 回退到 `createNewFile()`

### 用户反馈
- 历史记录状态：`OVERWRITE_FALLBACK`
- 消息：**"无法覆盖，已保存为新文件"**

### 不可修复
这是 Android 系统级限制，无法通过代码绕过。

---

## 2. FLAC 封面不写入

### 现象
转换为 FLAC 格式时，封面图片不会嵌入文件。

### 原因
- Android 原生 API 不支持 FLAC 元数据写入
- 需要引入第三方库（如 JAudioTagger ~2MB）
- V1 优先控制 APK 体积

### 代码位置
- `TagWriter.kt` → `writeTags()` → `format == "flac"` 分支跳过封面写入

### 用户反馈
- 历史记录状态：`SUCCESS_WITH_WARNING`
- 消息：**"FLAC 格式暂不支持封面写入"**

### V2 计划
考虑引入 JAudioTagger 或 FFmpeg 支持 FLAC 封面。

---

## 3. 历史记录功能边界

### 设计定位
历史记录 = **转换日志**，不是文件管理器。

### 支持的功能
- ✅ 显示转换结果（成功/跳过/失败/回退）
- ✅ 显示输出文件路径
- ✅ 显示使用的冲突策略
- ✅ 时间戳记录
- ✅ 调试模式显示更多信息

### 不支持的功能
- ❌ 从历史直接播放音乐
- ❌ 打开输出文件所在目录
- ❌ 批量管理/删除文件
- ❌ 重试失败的转换
- ❌ 无限历史存储（最多 200 条）

### 代码位置
- `HistoryRepository.kt` → `MAX_HISTORY_COUNT = 200`

---

## 4. 文件权限限制

### Android 分区存储的核心限制

| 操作 | 本应用创建的文件 | 其他应用创建的文件 |
|------|------------------|-------------------|
| 读取 | ✅ | ✅ (通过 MediaStore) |
| 覆盖 | ✅ | ❌ SecurityException |
| 删除 | ✅ | ❌ |

### 影响
- 无法强制覆盖其他 App 创建的同名文件
- 即使卸载重装 App，之前创建的文件也无法覆盖

### 代码位置
- `SAFManager.kt` → 使用 SAF 访问源目录
- `OutputWriter.kt` → 使用 MediaStore 写入 Music 目录

---

## 5. 测试检查清单

### 转换场景
- [ ] 新文件转换成功
- [ ] 同名文件 + SKIP → 跳过 + 删除源文件
- [ ] 同名文件 + OVERWRITE (本应用创建) → 覆盖成功
- [ ] 同名文件 + OVERWRITE (其他应用创建) → 回退 + 创建新文件
- [ ] 同名文件 + RENAME → 重命名成功

### 历史记录场景
- [ ] 转换后历史记录正确
- [ ] 点击历史显示详情
- [ ] 调试模式显示额外信息
- [ ] 超过 200 条自动清理

### 设置场景
- [ ] 冲突策略持久化
- [ ] 自动删除设置持久化
- [ ] 调试模式持久化
