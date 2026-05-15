# Android 离线翻译 App 开发计划（K2C Translator）

## 0. 当前进展
- App 已在真机跑通：离线翻译可用、手机端可编辑并保存词典、模型资产已稳定导出并能正常加载。
- 当前已确定技术路线：XML(Views) + Material Components（稳定优先，不走 Compose）。
- 当前已确定模型包格式：PyTorch Lite `.ptl`（压缩包内为 `archive/...`）+ vocab/config JSON。

## 1. 目标与范围

### 1.1 产品目标
- UI 现代化：主流工具类 App 视觉与交互（底部导航、卡片化信息、清晰按钮层级）。
- 三大页面：
  - 翻译（主页面）
  - 词典（编辑与保存）
  - 统计/履历（保存翻译日志、统计字数与耗时、导出 CSV）
- 模型更新能力：
  - B2：手动导入模型包 zip（Google Drive 选取 zip 文件导入）
  - B1：自动检查更新（读取一个固定 URL 的 `latest.json`，zip 放 Google Drive）
- Git 版本管理：源码与脚本入库；大文件（模型）不入库。

### 1.2 明确不做（本阶段）
- 在手机端把 `.pth/.pkl` 转成 `.ptl`（不在 Android 上做 PyTorch 导出与 pickle 解析）。
- xlsx 批量翻译（可后续再做）。

## 2. UI/交互规划（方案 A：底部导航）

### 2.1 页面结构
- `MainActivity`：承载底部导航与顶部栏（App 名称/版本/模型版本入口）。
- `TranslateFragment`：翻译主页面
- `UserDictFragment`：词典编辑页面
- `StatsFragment`：统计/履历页面
- `ModelUpdateActivity`（或 Fragment）：模型更新独立页面（入口放在统计页顶部按钮）

### 2.2 翻译页面（Translate）
- 顶部信息区（Card）：
  - 软件名称：K2C Translator
  - App 版本号：`BuildConfig.VERSION_NAME`
  - 构建日期：Build 常量（由 Gradle 生成）
  - 当前模型版本：从当前模型 `manifest.json` 读取（无则显示 `unknown`）
- 输入区：TextInputLayout（多行）+ 清空/粘贴
- 输出区：Card + 复制/分享
- 状态区：就绪/初始化/翻译中/失败原因

### 2.3 词典页面（UserDict）
- 文件来源提示：当前使用的是 `filesDir/translator/user_dict.md`
- 列表化编辑：
  - 搜索（按韩文/中文过滤）
  - 新增/编辑/删除（RecyclerView + 编辑页）
  - 保存后立即生效（保留现在的热更新机制）
- 一键恢复默认词典（从 assets 复制）

### 2.4 统计/履历页面（Stats）
- 履历列表（按时间倒序）：
  - 时间、耗时、输入字符数、输出字符数、`<unk>` 次数、模型版本
- 统计卡片：
  - 今日次数/本周次数
  - 总字符数、总耗时、平均耗时
  - `<unk>` 触发次数（用于词典补齐）
- 导出（统一 CSV）：
  - 导出 CSV 到 app 私有目录缓存
  - 通过系统分享/保存（SAF）导出给用户

## 3. 模型资产与“模型包 zip”规划

### 3.1 为什么不能用 `.pth/.pkl` 直接更新
- Android 端推理使用的是 PyTorch Lite `.ptl`，而 `.pth/.pkl` 属于训练产物，不能直接被手机端加载。
- 正确做法：训练产物在电脑端转成移动端资产，再发布给 App 使用。

### 3.2 移动端资产清单（zip 内内容）
- `encoder.ptl`
- `decoder.ptl`
- `ko_vocab.json`
- `zh_ivocab.json`
- `config.json`
- `manifest.json`

### 3.3 本地生成模型包 zip（发布流程）
- 使用脚本：[export_mobile_assets.py](file:///D:/pythonproject%202/Android%20APP/export_mobile_assets.py)
- 在工程根目录 PowerShell 执行：

```powershell
cd "D:\pythonproject 2\Android APP"
py -3 export_mobile_assets.py --make_zip --model_version 2026.05.14
```

- 生成文件：
  - `mobile_assets/`（中间产物）
  - `model_bundle_2026.05.14.zip`（发布文件，上传到 Google Drive）
  - 输出里包含 `model_bundle_sha256`（用于校验下载完整性）

### 3.4 B2：手动导入模型包（推荐优先落地）
- 用户在模型更新页面选择“从文件导入”
- 通过系统文件选择器（可直接选 Google Drive）选择 `model_bundle_*.zip`
- App 校验：
  - zip 是否包含 `manifest.json`
  - `encoder.ptl/decoder.ptl` 是否为有效 lite 结构（`archive/bytecode.pkl` 存在）
  - 校验 sha256（可选：优先校验 zip sha256）
- 解压位置：
  - `filesDir/models/<modelVersion>/...`
- 切换当前模型：
  - 写入 `filesDir/models/current.json`（或 SharedPreferences）

### 3.5 B1：自动检查更新（`latest.json` 放 Git，zip 放 Google Drive）
- `latest.json` 的作用：App 读取固定 URL，获取最新模型版本与 zip 下载地址。
- `latest.json` 位置：Git 仓库（raw 链接可访问即可）
- zip 位置：Google Drive（公开可下载链接）
- `latest.json` 示例结构：

```json
{
  "modelVersion": "2026.05.14",
  "zipUrl": "https://.../model_bundle_2026.05.14.zip",
  "zipSha256": "49cb1c5d7fc7cede0ec515b567718a572f581bca28b6152d591258e4a0628afc",
  "notes": "模型更新说明"
}
```

- App 流程：
  - 检查更新 → 比较版本 → 下载 zip → 校验 sha256 → 解压 → 切换 → 保留上一版以支持回滚

## 4. 日志、统计、导出（统一 CSV）

### 4.1 履历存储
- 使用 Room 数据库持久化每次翻译记录（用于统计与查询）
- 记录字段（建议）：
  - `timestampMs`
  - `durationMs`
  - `inputChars`、`outputChars`
  - `unkCount`
  - `modelVersion`
  - 可选：`inputPreview`（前 N 字，用于列表显示）

### 4.2 CSV 导出策略（Excel 兼容）
- 导出 CSV，编码使用 UTF-8 BOM（Excel 直接打开不乱码）
- 文件缓存目录：`filesDir/exports/`
- 导出动作：写入缓存 → 通过 SAF/分享导出到用户目录

## 5. Git 仓库与版本管理

### 5.1 仓库内容
- 入库：
  - Android 源码
  - Python 导出脚本与文档
  - `latest.json`（小文件）
- 不入库：
  - `.ptl` / 大模型 zip（放 Google Drive）
  - 本地缓存与构建产物（`.gradle/`, `**/build/`, `.idea/`, `local.properties`）

### 5.2 App 版本策略
- `versionName`：语义化版本（如 `1.0.0`、`1.1.0`）
- `versionCode`：递增整数
- 构建日期：在构建时写入 BuildConfig 常量，供 UI 展示

## 6. 开发里程碑（按可交付拆分）

### M1：UI 改版与三页面骨架
- 底部导航 + 顶部信息栏
- 翻译页迁移到 Fragment
- 词典页迁移到 Fragment（保留现有编辑保存能力）
- 统计页占位 + 入口按钮（模型更新页面）

### M2：履历与统计 + CSV 导出
- 翻译完成写入数据库
- 统计页展示列表与统计卡片
- CSV 导出与分享

### M3：模型更新页面（B2 优先，B1 增量）
- B2：导入 zip → 校验 → 解压 → 切换 → 回滚
- B1：读取 Git 上 `latest.json` → 下载 zip → 校验 → 切换

## 7. 验收标准（每个里程碑必须满足）
- M1：三页面可用，翻译/词典功能不回退
- M2：每次翻译都有履历；统计正确；CSV 可导出并在 Excel 正常打开
- M3：导入模型包后立即生效；错误提示清晰；可回滚到上一版
