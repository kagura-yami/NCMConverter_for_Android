# Changelog

All notable changes to this project will be documented in this file.

---

## [1.0.0] - 2026-02-06

### 🎉 初始版本发布

#### 核心功能
- **NCM 解密**：本地解密网易云音乐 NCM 格式，输出 FLAC/MP3
- **元数据写入**：自动写入标题、艺术家、专辑信息
- **封面嵌入**：MP3 格式支持封面嵌入（FLAC 暂不支持）
- **SAF 授权**：使用 Storage Access Framework 安全访问文件

#### 冲突处理
- **跳过 (SKIP)**：文件已存在时跳过，但仍删除源文件（如开启）
- **覆盖 (OVERWRITE)**：尝试覆盖，权限受限时自动回退为新文件
- **重命名 (RENAME)**：自动添加 (1), (2) 后缀

#### 历史记录
- 本地 Room 数据库存储
- 支持 5 种状态：成功、警告、跳过、失败、覆盖回退
- 最多保留 200 条记录
- 调试模式显示更多技术信息

#### 设置选项
- 冲突策略选择
- 自动删除源文件开关
- 调试模式开关

#### 已知限制
| 限制 | 原因 |
|------|------|
| FLAC 封面不写入 | Android API 限制，需第三方库 |
| 覆盖可能生成 (1) 后缀 | 分区存储限制，无法覆盖非本应用创建的文件 |
| 历史不支持播放 | 设计定位为日志，非文件管理器 |

---

## [2.0.0] - 计划中

### V2 预期功能

#### 🎯 高优先级
- [ ] **FLAC 封面支持**：引入 JAudioTagger 或 FFmpeg
- [ ] **批量选择**：支持选择部分文件转换
- [ ] **进度详情**：显示当前正在处理的文件名

#### 📦 中优先级
- [ ] **打开文件位置**：从历史记录直接跳转文件管理器
- [ ] **通知栏进度**：后台转换时显示通知
- [ ] **深色主题优化**：更好的深色模式支持

#### 🔧 低优先级
- [ ] **重试失败**：从历史记录重试失败的文件
- [ ] **自定义输出目录**：允许用户选择输出位置
- [ ] **格式转换选项**：强制输出为 MP3 或 FLAC

---

## 技术债务清理计划

- [ ] 移除调试日志 (android.util.Log)
- [ ] 引入依赖注入框架 (Hilt)
- [ ] 添加单元测试
- [ ] 代码混淆配置

---

## 文件结构

```
app/src/main/java/com/example/ncmconverter/
├── domain/
│   ├── ConvertUseCase.kt      # 批量转换逻辑
│   ├── ScanNcmUseCase.kt      # 文件扫描逻辑
│   └── model/                 # 数据模型
├── infra/
│   ├── SAFManager.kt          # SAF 权限管理
│   ├── FileCopier.kt          # 文件复制
│   ├── NcmDecoder.kt          # NDK 解密调用
│   ├── TagWriter.kt           # 元数据写入
│   ├── OutputWriter.kt        # 输出写入
│   ├── SettingsRepository.kt  # 设置持久化
│   └── HistoryRepository.kt   # 历史记录管理
├── data/
│   ├── AppDatabase.kt         # Room 数据库
│   ├── Converters.kt          # 类型转换器
│   ├── dao/                   # DAO 接口
│   └── entity/                # 实体类
└── ui/
    ├── MainActivity.kt        # 主活动
    ├── MainScreen.kt          # 主界面
    ├── MainViewModel.kt       # 主 ViewModel
    ├── SettingsScreen.kt      # 设置界面
    ├── HistoryScreen.kt       # 历史界面
    └── HistoryViewModel.kt    # 历史 ViewModel
```
