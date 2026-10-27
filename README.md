# 云笺 · Yunjian

[![Release](https://img.shields.io/github/v/release/yunqishuhai/yunjian)](https://github.com/yunqishuhai/yunjian/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)](https://developer.android.com/about/versions/oreo)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

轻量的非官方米游社 Android 私聊客户端，基于 Kotlin、Jetpack Compose 和 Material 3。

## 安装

从 [GitHub Releases](https://github.com/yunqishuhai/yunjian/releases/latest) 下载最新 APK，安装后登录米游社账号。应用内可手动检查更新。

## 功能

- 收发文字、图片，支持拍照、官方表情与自定义表情、收藏分组。
- 聊天记录缓存、搜索与定位，导出 HTML、JSON 及图片附件。
- 多账号切换、消息通知、会话置顶与免打扰。
- 双方头像、自定义聊天背景，雾蓝、冰蓝、竹青、浅紫、樱粉配色及明暗模式。

图片通过临时自定义表情发送，需等待平台审核。只同步当前账号；后台通知需开启后台同步并允许系统后台运行。暂不支持主动新建私聊、删除会话和语音。

## 构建

需要 JDK 17+、Android SDK 36 和 Build Tools 36.0.0。

```sh
./gradlew assembleDebug
```

Windows 使用 `gradlew.bat assembleDebug`。SDK 路径配置在 `local.properties` 或 `ANDROID_HOME`；APK 位于 `app/build/outputs/apk/debug/`。

[构建与测试](BUILDING.md) · [签名与发布](RELEASING.md) · [隐私说明](PRIVACY.md) · [安全报告](SECURITY.md)

## 许可证

源码采用 [MIT License](LICENSE)，图标与第三方素材见 [NOTICE.md](NOTICE.md)。本项目与米游社及米哈游官方无隶属关系。
