# ⑨Player

Audiobook player with Anki support.

[English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

## Audiobooks

After tapping `+` at the bottom-right:

1. Select the audiobook folder.
2. Select audio and SRT.
3. If "auto move to audiobook folder" is enabled, the app will:
   - Create an `AudX` folder under your audiobook folder.
   - Move audio and SRT into that folder.

```text
Example:

audiobook-folder/
├── Aud1/
│   ├── 1.mp3
│   └── 1.srt
└── Aud2/
    ├── 2.m4b
    └── 2.srt
```

SRT reference:
[SubPlz](https://github.com/kanjieater/SubPlz)

## Anki

Supports Yomitan vocabulary, pitch accent, and frequency dictionaries.

Collection
- [marv](https://github.com/MarvNC/yomitan-dictionaries?tab=readme-ov-file#dictionary-collection)
- [uchagikun](https://github.com/SalwynnJP/yomitan-dictionaries)
- [Shoui](https://learnjapanese.moe/yomichan/#acquiring-dictionaries)

You can refer to the settings below:

<p>
  <img src="./docs/images/anki-settings-1.jpg" width="260" alt="Anki settings 1" />
  <img src="./docs/images/anki-settings-2.jpg" width="260" alt="Anki settings 2" />
</p>

```text
{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}
{cut-audio} sentence audio
{book-title} audio file name
```

Anki template: [Lapis](https://github.com/donkuri/lapis)

## Controller

To use "Disconnect controller Bluetooth":

1. Install and configure Shizuku.
2. Go to `Settings -> Controller Bluetooth`.
3. Tap request Shizuku permission.

## Audio

Use local TTS or import [android.db](https://github.com/KamWithK/AnkiconnectAndroid?tab=readme-ov-file#additional-instructions-local-audio).

## Control Mode

In control mode, the screen does not turn off naturally.

## Floating Bubble

Enable in `Settings -> Audiobooks`.

While playing an audiobook, return to home/switch apps to show it.

## Credits

- [hoshidicts](https://github.com/Manhhao/hoshidicts)
- [Ankiconnect Android](https://github.com/KamWithK/AnkiconnectAndroid) (local audio)
- [Yomitan](https://github.com/yomidevs/yomitan)

## License

This project is licensed under [GPLv3.0](LICENSE).
