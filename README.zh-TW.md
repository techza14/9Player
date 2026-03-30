# ⑨Player

![App Icon](./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

有聲書播放器 支援Anki

[English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

## 特性

播放有聲書  
匯出到 Anki（目前僅支援日語）

### 基礎播放器功能
- 計時、倍速等基礎功能
- 封面/字幕切換
- 書架/列表切換

### m4b
- 章節
- 點擊切換顯示章節/總時長與進度

### 擴展功能
- [控制模式](#控制模式)
- [懸浮球](#懸浮球)
- 收藏句子
- [手柄](#手柄)支援

### [Mine 流程](#mine流程)
- [音訊](#音訊)
- 導入 Yomitan 辭典
- 點擊字幕查詞
- 匯出到 Anki

---

# 添加有聲書

點擊右下角 `+` 後：

1. 選擇有聲書資料夾
2. 選擇音訊和 SRT
3. 如果選擇自動移動到有聲書資料夾
   App 會自動：
   - 在有聲書資料夾下新建一個 `AudX` 資料夾
   - 把音訊和 SRT 移動到該資料夾

```text
目錄示例：

有聲書資料夾/
├── Aud1/
│   ├── 1.mp3
│   └── 1.srt
└── Aud2/
    ├── 2.m4b
    └── 2.srt
```

SRT 可參考：
[SubPlz](https://github.com/kanjieater/SubPlz)

## Mine流程

支援 Yomitan 詞彙、音調、詞頻辭典

辭典連結
- [明鏡日漢雙解辭典](https://forum.freemdict.com/t/topic/38630)
- [小學館日中辭典第三版](https://github.com/DgnFBJkH5k/Golden-Parcel)

Collection
- [marv](https://github.com/MarvNC/yomitan-dictionaries?tab=readme-ov-file#dictionary-collection)
- [uchagikun](https://github.com/SalwynnJP/yomitan-dictionaries)
- [Shoui](https://learnjapanese.moe/yomichan/#acquiring-dictionaries)

可參考以下設定：

<p>
  <img src="./docs/images/anki-settings-1.jpg" width="260" alt="Anki設定1" />
  <img src="./docs/images/anki-settings-2.jpg" width="260" alt="Anki設定2" />
</p>

```text
{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}
```
```text
{cut-audio} 句子音訊
{book-title} 音訊文件名字
```

Anki模板：[Lapis](https://github.com/donkuri/lapis)

## 手柄

如果要使用「斷開手柄藍牙」功能：

1. 先安裝並配置 Shizuku
2. 進入 設定 -> 手柄藍牙
3. 點擊請求 Shizuku 權限

## 音訊

可使用本地 TTS 或導入 [android.db](https://github.com/KamWithK/AnkiconnectAndroid?tab=readme-ov-file#additional-instructions-local-audio)

## 控制模式

用於手勢操控。

處於控制模式時，螢幕不會自然熄屏。

## 懸浮球

懸浮球在 設定 -> 有聲書 可開啟。

播放有聲書時回到桌面/切換 app 即可顯示。

## 特別感謝

- [hoshidicts](https://github.com/Manhhao/hoshidicts)
- [Ankiconnect Android](https://github.com/KamWithK/AnkiconnectAndroid) 本地音訊
- [Yomitan](https://github.com/yomidevs/yomitan)

## License

本專案採用 [GPLv3.0](LICENSE) 開源。
