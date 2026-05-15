# 移动端资产说明（Android 侧使用）

## 产物目录

运行 `export_mobile_assets.run_export()` 后，默认生成目录：
- `mobile_assets/`
  - `encoder.ptl`
  - `decoder.ptl`
  - `ko_vocab.json`
  - `zh_ivocab.json`
  - `config.json`

## Android 工程内建议放置位置

- `app/src/main/assets/translator/`
  - `encoder.ptl`
  - `decoder.ptl`
  - `ko_vocab.json`
  - `zh_ivocab.json`
  - `config.json`
  - `user_dict.md`（内置默认词典，首次启动复制到 app 私有目录供用户编辑/导入覆盖）

## 运行时路径建议

- 模型与词表：从 assets 读取并加载到内存（只读）
- 用户词典：首次从 assets 复制到 `filesDir/user_dict.md`，后续总是从 `filesDir` 读取

## config.json 字段（Android 端解码用）

- `ko.sos_id / ko.eos_id / ko.unk_id`
- `zh.sos_id / zh.eos_id / zh.unk_id / zh.pad_id`
- `model.*`：用于一致性校验（可选）
- `decode.max_len_token`、`decode.repeat_penalty`
