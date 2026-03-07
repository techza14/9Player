# ⑨Player

有声书播放器，支持 Anki 挖词。当前仍在开发中。

## 有声书

点击右下角 `+书籍` 后：

1. 选择有声书文件夹
2. 选择音频和 SRT
3. App 会自动：
   - 在有声书文件夹下新建一个 `AudX` 文件夹
   - 把音频和 SRT 移动到该文件夹

目录示例：

```text
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
2. 进入 `设置 -> 手柄蓝牙`
3. 点击请求 Shizuku 权限

## 控制模式

处于控制模式时，屏幕不会自然熄屏。

## Anki

目前不支持音调、词频等辞典字段。

可参考以下设置：

<p>
  <img src="https://github.com/user-attachments/assets/97a6d64a-4004-407c-b178-2935ef046a71" width="260" alt="Anki设置1" />
  <img src="https://github.com/user-attachments/assets/00299aa3-0e81-4917-85d4-8833ffd0ceec" width="260" alt="Anki设置2" />
</p>

本项目采用 `GNU General Public License v3.0`（`GPL-3.0-only`）开源。
完整协议见 [LICENSE](LICENSE)。
