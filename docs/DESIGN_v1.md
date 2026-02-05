# NCM 转无损音频 Android App — 设计定稿 (Draft v1)

> **版本**: v1.0  
> **日期**: 2026-02-05  
> **状态**: 设计定稿，**已锁定** — 所有决策点已确认  
> **目的**: 作为后续实现与 review 的唯一参考

---

## 1. 项目概述

### 1.1 目标

开发一个 Android 12+ 的 Kotlin 工具 App，用于：
- 通过 SAF 授权访问网易云音乐下载目录
- 扫描并解密 `.ncm` 文件
- 提取音频与 metadata，写入带完整 Tag（含封面）的音频文件
- 输出到公共 Music 目录

### 1.2 核心约束

| 约束 | 说明 |
|------|------|
| 不 root | 仅使用 SAF 授权访问外部目录 |
| NDK 只接受普通文件路径 | 不接触 SAF/URI，需先复制到 cacheDir |
| 单文件原子性 | 先写 `.tmp`，完成后 rename |
| 冲突策略由 Kotlin 层决定 | Domain 层统一处理去重与冲突 |

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              UI Layer (Compose)                             │
│  ┌─────────────────────┐   ┌─────────────────────┐   ┌────────────────────┐ │
│  │   MainScreen        │   │   SettingsScreen    │   │   ProgressDialog   │ │
│  │   (文件列表/状态)     │   │   (冲突策略/自动删除) │   │   (转换进度)        │ │
│  └─────────────────────┘   └─────────────────────┘   └────────────────────┘ │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ ViewModel (仅状态感知，无业务判断)
┌───────────────────────────────────▼─────────────────────────────────────────┐
│                         Domain Layer (业务核心)                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────────────────────┐ │
│  │ ScanNcmUseCase   │  │ ConvertUseCase   │  │ SettingsRepository         │ │
│  │ (流式扫描)        │  │ (批量控制/去重)   │  │ (DataStore)               │ │
│  └──────────────────┘  └──────────────────┘  └────────────────────────────┘ │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼─────────────────────────────────────────┐
│                            Infrastructure Layer                             │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ │
│  │ SAFManager │ │ FileCopier │ │ NcmDecoder │ │ TagWriter  │ │OutputWriter│ │
│  │ (授权/枚举) │ │ (URI→Cache)│ │  (JNI/NDK) │ │ (metadata) │ │(Music目录) │ │
│  └────────────┘ └────────────┘ └────────────┘ └────────────┘ └────────────┘ │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼─────────────────────────────────────────┐
│                              NDK Layer (C++)                                │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  libncmdecrypt.so                                                       ││
│  │  - AES 解密核心 / RC4 密钥扩展 / 音频数据提取 / Metadata JSON 输出       ││
│  └─────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 层级职责定义

### 3.1 UI Layer

| 组件 | 职责 |
|------|------|
| MainScreen | 显示扫描到的 NCM 文件列表、转换状态、一键转换按钮 |
| SettingsScreen | 冲突策略选择、是否自动删除原文件 |
| MainViewModel | 状态管理，订阅 Domain 层 StateFlow，**不包含任何业务判断** |

### 3.2 Domain Layer

| 组件 | 职责 |
|------|------|
| ScanNcmUseCase | 流式扫描 NCM 文件，返回 `Flow<NcmFileRef>` |
| ConvertUseCase | 批量控制、失败判定、是否中断、去重策略、重试策略 |
| SettingsRepository | 持久化用户设置 (DataStore) |

**Domain 层负责所有业务逻辑判断，包括：**
- 去重检测
- 并发控制
- 重试策略
- 中断决策

### 3.3 Infrastructure Layer

| 组件 | 职责 |
|------|------|
| SAFManager | 请求 `ACTION_OPEN_DOCUMENT_TREE`、持久化 URI 权限、流式枚举 `.ncm` |
| FileCopier | 从 SAF URI 复制到 `cacheDir/input/` |
| NcmDecoder | JNI 桥接，调用 C++ 解密，只接受绝对路径 |
| TagWriter | 写入 ID3v2/Vorbis Comment + 封面（使用 JAudioTagger）。**失败时不视为转换失败，归类为 SuccessWithWarning** |
| OutputWriter | 写入公共 Music 目录，处理原子写入。**只负责写入与重命名，不负责冲突决策（由 Domain 层提前决定最终文件名）**。**若 .tmp 插入成功但 rename/update 失败，必须删除孤立 .tmp 文件** |

### 3.4 NDK Layer

```
native int decryptNcm(
    String inputPath,   // cacheDir/input/xxx.ncm
    String outputDir    // cacheDir/output/
);

返回值:
  0  = 成功
 -1  = 文件读取失败
 -2  = 非法 NCM 格式
 -3  = 解密失败

输出文件:
  outputDir/xxx.mp3 (或 .flac，取决于源格式)
  outputDir/xxx.meta.json (含 title, artist, album, cover base64)
```

**JNI 返回值与 Kotlin Failure 映射关系：**

| JNI 返回值 | Kotlin Failure | 说明 |
|------------|----------------|------|
| 0 | — | 成功 |
| -1 | `CorruptedFile` | 文件 IO 错误 |
| -2 | `CorruptedFile` | 非法 NCM 格式 |
| -3 | `DecryptFailed` | 解密算法失败 |

**【重要】format 可信来源：**

> `NcmMetadata.format` 以 NDK 解密后**实际输出音频的扩展名**为准，不依赖原文件名或 NCM 内嵌 metadata 声明字段。
>
> 原因：存在历史 NCM 样本，其 metadata 声明为 mp3，实际音频为 flac。

---

## 4. 数据流（单文件处理）

```
     ┌──────────────────────────────────────────────────────────────┐
     │                   SAF 授权目录 (网易云下载)                     │
     └────────────────────────┬─────────────────────────────────────┘
                              │ ① 流式枚举 .ncm (Cursor 分页)
                              ▼
     ┌──────────────────────────────────────────────────────────────┐
     │        NcmFileRef { documentId, displayName, size }          │
     └────────────────────────┬─────────────────────────────────────┘
                              │ ② 复制到 cacheDir/input/
                              ▼
     ┌──────────────────────────────────────────────────────────────┐
     │           cacheDir/input/song.ncm (普通文件路径)              │
     └────────────────────────┬─────────────────────────────────────┘
                              │ ③ NDK 解密
                              ▼
     ┌──────────────────────────────────────────────────────────────┐
     │  cacheDir/output/song.mp3    +    song.meta.json             │
     │          (裸音频)                  (封面 base64 + 元数据)      │
     └────────────────────────┬─────────────────────────────────────┘
                              │ ④ TagWriter 写入 metadata + 封面
                              ▼
     ┌──────────────────────────────────────────────────────────────┐
     │  cacheDir/output/song_tagged.mp3  (带完整 Tag)               │
     └────────────────────────┬─────────────────────────────────────┘
                              │ ⑤ 写入公共 Music (原子性)
                              ▼
     ┌──────────────────────────────────────────────────────────────┐
     │  Music/song.tmp.mp3  →  rename  →  song.mp3                  │
     │       (根据冲突策略: skip / overwrite / song(1).mp3)          │
     └────────────────────────┬─────────────────────────────────────┘
                              │ ⑥ 成功后删除原 NCM (通过 SAF)
                              ▼
     ┌──────────────────────────────────────────────────────────────┐
     │        DocumentsContract.deleteDocument(uri)                 │
     └──────────────────────────────────────────────────────────────┘
```

---

## 5. 核心数据结构

```kotlin
// ===== 扫描结果 =====
data class NcmFileRef(
    val documentId: String,   // 仅保留 ID，不持有 DocumentFile
    val displayName: String,
    val size: Long
)

/**
 * 【重要约束】documentId 与 URI 的关系：
 * - documentId 仅作为 DocumentsContract 操作参数使用
 * - 实际 URI 由 SAFManager 根据 treeUri + documentId 临时构造
 * - Domain 层不直接拼接或持有 URI
 * - 禁止在 Domain 层缓存 Uri 对象（可能导致权限失效）
 */

// ===== 解密输出 metadata =====
data class NcmMetadata(
    val musicName: String,
    val artist: List<String>,
    val album: String,
    val format: String,       // "mp3" or "flac"
    val coverBase64: String?, // 封面图 base64
    val bitrate: Int?
)

// ===== 转换结果 (三态) =====
sealed class ConvertResult {
    data class Success(val outputPath: String) : ConvertResult()
    data class SuccessWithWarning(val outputPath: String, val warning: String) : ConvertResult()
    data class Failed(val failure: ConvertFailure) : ConvertResult()
}

// ===== 失败类型 =====
sealed class ConvertFailure {
    // 可恢复失败 (不中断批量)
    data class CorruptedFile(val file: NcmFileRef, val msg: String) : ConvertFailure()
    data class DecryptFailed(val file: NcmFileRef, val code: Int) : ConvertFailure()
    
    // 致命失败 (立即中断)
    object DiskFull : ConvertFailure()
    object PermissionLost : ConvertFailure()
    object OutputDirUnwritable : ConvertFailure()
}

// ===== 用户设置 =====
data class AppSettings(
    val conflictPolicy: ConflictPolicy,
    val autoDeleteSource: Boolean
)

enum class ConflictPolicy { 
    SKIP,       // 跳过
    OVERWRITE,  // 覆盖
    RENAME      // 重命名 (song(1).mp3)
}
```

---

## 6. 策略定义

### 6.1 SAF 与文件扫描策略

| 规则 | 说明 |
|------|------|
| 流式扫描 | 使用 `DocumentsContract.queryChildDocuments()` + Cursor 分页 |
| 不一次性加载 | 禁止 `DocumentFile.fromTreeUri().listFiles()` 全量加载 |
| 按需打开流 | 每个文件仅在需要时 `openInputStream`，用完立即关闭 |
| 仅保留引用 | Cursor 遍历时仅保留 `documentId`，不持有 DocumentFile 对象 |

### 6.2 输出格式策略 (不升不降)

| 源格式 | 输出格式 | 说明 |
|--------|----------|------|
| 无损 (FLAC) | FLAC | 保持原格式，直接提取 |
| 有损 (MP3) | MP3 | 保持原格式，不做转码 |

**原则：保留原始采样率、位深，不做无意义的格式转换。**

### 6.3 并发与执行模型

| 规则 | 说明 |
|------|------|
| 不常驻后台 | 无 FileObserver / WorkManager 周期任务 |
| 不监听目录 | 仅用户点击"开始"时执行 |
| 串行执行 | 批量转换默认单线程串行 |
| JNI 不并发 | JNI 调用加 `@Synchronized`，不允许并发解密 |

### 6.4 去重与冲突策略

| 规则 | 说明 |
|------|------|
| 检测时机 | 转换前检测 Music 目录是否已存在目标文件 |
| 判定标准 | 按文件名（含扩展名）匹配 |
| 冲突选项 | 跳过 / 覆盖 / 重命名 |
| 批量应用 | 支持"本次操作全部使用该选项" |
| 处理层级 | Domain 层 (`ConvertUseCase`) 统一处理 |

### 6.5 失败与中断策略

| 失败类型 | 分类 | 处理 |
|----------|------|------|
| 单个 NCM 格式损坏 | **可恢复** | 跳过，记录，继续下一个 |
| 单个 NCM 解密失败 | **可恢复** | 跳过，记录，继续下一个 |
| cacheDir 磁盘满 | **致命** | 立即中断，向上层暴露原因 |
| SAF 权限丢失 | **致命** | 立即中断，向上层暴露原因 |
| Music 目录不可写 | **致命** | 立即中断，向上层暴露原因 |
| 用户主动取消 | **致命** | 立即中断，清理临时文件 |

### 6.6 用户取消处理

| 规则 | 说明 |
|------|------|
| 中断检查 | 每个文件处理前检查 `isActive` 信号 |
| 已成功文件 | **保留**，不回滚 |
| 临时文件 | 清理 cacheDir 中的临时文件 |

**cacheDir 清理范围（避免误删）：**

```
仅清理以下子目录：
  - cacheDir/input/
  - cacheDir/output/

不清理：
  - cacheDir 根目录下的其他文件
  - 任何非本 App 创建的文件
```

### 6.7 原文件删除策略

| 规则 | 说明 |
|------|------|
| 删除时机 | 单个文件转换成功后立即删除 |
| 删除方式 | `DocumentsContract.deleteDocument(uri)` |
| 删除失败 | 记录 warning，不阻塞批量任务 |

---

## 7. 目录结构规划

```
app/
├── src/main/
│   ├── java/com/example/ncmconverter/
│   │   ├── ui/
│   │   │   ├── MainActivity.kt
│   │   │   ├── MainScreen.kt          (Compose)
│   │   │   ├── SettingsScreen.kt      (Compose)
│   │   │   └── MainViewModel.kt
│   │   ├── domain/
│   │   │   ├── ScanNcmUseCase.kt
│   │   │   ├── ConvertUseCase.kt
│   │   │   └── model/
│   │   │       ├── NcmFileRef.kt
│   │   │       ├── NcmMetadata.kt
│   │   │       ├── ConvertResult.kt
│   │   │       ├── ConvertFailure.kt
│   │   │       └── AppSettings.kt
│   │   ├── infra/
│   │   │   ├── SAFManager.kt
│   │   │   ├── FileCopier.kt
│   │   │   ├── NcmDecoder.kt          (JNI wrapper)
│   │   │   ├── TagWriter.kt
│   │   │   ├── OutputWriter.kt
│   │   │   └── SettingsRepository.kt
│   │   └── NcmConverterApp.kt         (Application)
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── ncm_decrypt.cpp            (核心解密)
│   │   ├── ncm_decrypt.h
│   │   └── jni_bridge.cpp             (JNI 入口)
│   └── res/
│       └── ...
└── build.gradle.kts
```

---

## 8. 已识别风险与应对

| # | 风险 | 应对方案 |
|---|------|----------|
| 1 | NCM 内嵌格式只能解密后确定 | 两阶段命名：先 `.tmp`，解密后根据实际格式确定扩展名 |
| 2 | 用户主动中断批量任务 | 每步检查 `isActive`，中断时清理 cache，保留已成功文件 |
| 3 | MediaStore rename 非原子 | 先插入 `.tmp`，成功后 `update` 改名；失败时 `delete` |
| 4 | SAF 删除原文件可能失败 | 删除失败记录 warning，不阻塞批量任务 |

---

## 9. 决策确认记录

> **状态**: 全部已确认 (2026-02-05)

| # | 决策 | 确认状态 |
|---|------|----------|
| A | 重复判定：首版仅按目标输出文件名判重，不做 hash/音频指纹比对 | ✅ 已确认 |
| B | 有损源（MP3/AAC）保持原格式输出，不提供"强制转 FLAC"选项 | ✅ 已确认 |
| C | 用户取消后保留已成功转换的文件，仅清理 cacheDir 中间文件 | ✅ 已确认 |
| D | 致命失败仅限 §6.5 表格所列类型，其他均视为可恢复失败 | ✅ 已确认 |

---

## 10. 变更记录

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-02-05 | 初始设计定稿 |
| v1.1 | 2026-02-05 | 补充5项实现约束：documentId/URI关系、TagWriter失败归类、OutputWriter职责边界、cacheDir清理范围、JNI返回值映射 |
| v1.2 | 2026-02-05 | 补充2项防御性约束：format以NDK实际输出为准、OutputWriter失败时删除孤立.tmp文件 |

---

**文档结束**
