# ⑨Player

有声书播放器 支持anki 半成品凑合用

## 有声书

点击右下角 +书籍 后：

1. 选择有声书文件夹
2. 选择音频和 SRT
3. App 会自动：
   - 在有声书文件夹下新建一个 AudX 文件夹
   - 把音频和 SRT 移动到该文件夹
```
目录示例：
text
有声书文件夹/
├── Aud1/
│   ├── 1.mp3
│   └── 1.srt
└── Aud2/
    ├── 2.m4b
    └── 2.srt
```

SRT 可参考：
[SubPlz](https://github.com/kanjieater/SubPlz)


## 手柄

如果要使用“断开手柄蓝牙”功能：

1. 先安装并配置 Shizuku
2. 进入 设置 -> 手柄蓝牙
3. 点击请求 Shizuku 权限

！！单独断开手柄蓝牙功能可能不生效

## 控制模式

处于控制模式时，屏幕不会自然熄屏。

## 悬浮球

悬浮球 设置 -> 有声书 可开启
播放有声书时 回到桌面/切换app 即可显示
## Anki

目前不支持音调、词频等辞典。

不保证其他变量可用 可参考以下设置：

<p>
  <img src="./docs/images/anki-settings-1.jpg" width="260" alt="Anki设置1" />
  <img src="./docs/images/anki-settings-2.jpg" width="260" alt="Anki设置2" />
</p>

```cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}```
{cut-audio} 句子音频
{bookt-title} 音频文件标题

Anki模板:[Lapis](https://github.com/donkuri/lapis)
## 待改善
断开手柄蓝牙不生效

##  特别感谢
[hoshidicts](https://github.com/Manhhao/hoshidicts)

## License

本项目采用 `GNU General Public License v3.0`（`GPL-3.0-only`）开源。
完整协议见 [LICENSE](LICENSE)。
