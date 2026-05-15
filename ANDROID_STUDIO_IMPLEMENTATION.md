# Android Studio 实施说明（从 0 做到可用 App）

## 1. 新建工程

- Android Studio → New Project
- 语言：Kotlin
- 最低版本：建议 Android 8.0（API 26）或更高（便于兼容与性能）
- 包名自定

## 2. 放入资产（assets）

将以下文件放入 Android 工程：

- `app/src/main/assets/translator/`
  - `encoder.ptl`、`decoder.ptl`（由 `export_mobile_assets.run_export()` 生成）
  - `ko_vocab.json`、`zh_ivocab.json`
  - `config.json`
  - `user_dict.md`（内置默认词典）

目录结构参考：[MOBILE_ASSETS_README.md](file:///d:/pythonproject%202/Android%20APP/MOBILE_ASSETS_README.md)

## 3. 依赖（Gradle）

在 `app/build.gradle`（或 `build.gradle.kts`）添加 PyTorch Mobile 依赖（示例，具体版本以你本机环境可用为准）：

```gradle
dependencies {
  implementation "org.pytorch:pytorch_android_lite:1.13.1"
  implementation "org.pytorch:pytorch_android_torchvision_lite:1.13.1"
}
```

如果你只用基础推理，通常只需要 `pytorch_android_lite`。

## 4. 代码组织建议

- `translator/`
  - `AssetFiles.kt`：assets → filesDir 复制、返回可加载路径
  - `UserDict.kt`：解析 `user_dict.md`，提供结构化数据
  - `Vocabs.kt`：加载 `ko_vocab.json`、`zh_ivocab.json`、`config.json`
  - `TranslatorEngine.kt`：实现 translateSentence 与 decode（greedy + repeat_penalty）
- `ui/`
  - `MainActivity.kt`：输入/输出 UI + 后台线程调用 TranslatorEngine

本项目目录中提供了一套可直接复制进 Android 工程的 Kotlin 模板：
- `android_template/translator/`
  - `AssetFiles.kt`
  - `Vocabs.kt`
  - `UserDict.kt`
  - `TranslatorEngine.kt`

## 5. Kotlin 侧实现要点（与 Python 对齐）

### 5.1 规则逻辑必须对齐

对应 Python 逻辑文件：
- [实时翻译测试_V3.0(greedy).py](file:///d:/pythonproject%202/Android%20APP/%E5%AE%9E%E6%97%B6%E7%BF%BB%E8%AF%91%E6%B5%8B%E8%AF%95_V3.0(greedy).py)

需要逐项对齐：
- `clean_text` 的过滤字符范围
- 括号分割 `split_by_parentheses`（含中文括号）
- `dedupe_repeated_cjk`
- user_dict 解析逻辑（分词/直译/替换/术语/仅模型词）
- 混排切分与韩文块识别
- 助词拆分：`이/의/는/를`
- “词典优先，未命中走模型”的顺序与规则

### 5.2 推理（encoder/decoder）

解码必须对齐 Python 的 greedy 行为：
- 源 token：`[<sos>, token_or_unk, <eos>]`
- 逐步生成：`<sos>` 开始，直到 `<eos>` 或 `max_len`
- repeat penalty：对已经出现过的 token id 做 `logits[id] /= repeat_penalty`
- 拼接输出时过滤 `<sos>/<eos>/<pad>`

### 5.3 线程模型

- 模型加载：后台执行，加载一次常驻
- 翻译执行：后台线程/协程执行，UI 只展示状态与结果

## 6. 回归验证（强烈建议先做）

- 在电脑上先生成 `golden_cases.json`：
  - `golden_inputs.txt` 每行一个输入
  - 用 [generate_golden_cases.py](file:///d:/pythonproject%202/Android%20APP/generate_golden_cases.py) 生成
- Android 端做一键回归页（开发阶段用）：
  - 从 assets 读取 golden_cases.json
  - 逐条翻译并对照 output
  - 输出不一致的 case 列表，便于定位差异
