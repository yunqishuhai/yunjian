# 云笺 · Yunjian

使用 Kotlin、Jetpack Compose 和 Material 3 构建的非官方米游社 Android 私聊客户端。

## 功能

- 文字、图片、官方表情、自定义表情与本地收藏分组。
- 历史缓存、关键词与日期搜索、会话内定位。
- ZIP 导出离线 HTML、JSON 和可选图片附件。
- 多账号加密保存与切换，账号之间缓存、收藏、背景分别保存；只同步当前账号。
- 消息通知、会话置顶与免打扰、双方头像、默认或单个会话背景。
- GitHub Releases 手动检查更新、下载校验与系统确认安装。

图片入口使用临时自定义表情：上传后等待平台审核，通过后发送，确认历史可见后清理此次新增的临时项。资源的长期可用性由平台决定。新建私聊、删除会话、语音、多账号同时接收和完整安全挑战登录流程暂未提供。

本项目处于开发预览阶段，包名为 `io.github.miyouchat`。第三方接口可能变动；仅使用自己的账号，通过应用正常登录。凭据与记录不随源码分发。后台消息通过可选的常驻服务轮询，强行停止后需重新打开；长期省电/网络恢复行为仍需持续验证。

## 构建

需要 JDK、Android SDK Platform 36 和 Build Tools 36.0.0。本项目已验证 JDK 25 / Gradle 9.1 / AGP 9.0.1。

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows：

```powershell
$env:JAVA_HOME='<JDK folder>'
.\tools\build-android.ps1 -Tasks @('assembleDebug','testDebugUnitTest','lintDebug')
```

SDK 路径放在本地 `local.properties` 或 `ANDROID_HOME`，不要提交个人配置。APK 位于 `app/build/outputs/apk/debug/`。

版本和更新渠道见 `release.properties`；兼容配置 `protocol.properties` 中的静态签名参数来自公开客户端，不是个人 API Key 或账号凭据。

更多说明：[构建与测试](BUILDING.md) · [签名与发布](RELEASING.md) · [隐私说明](PRIVACY.md) · [安全报告](SECURITY.md)。

## 源码与素材

源码采用 [MIT License](LICENSE)。公开仓库提供原创矢量默认图标；个人选图、原始调查包、手机截图、聊天样本、账号凭据、正式签名私钥和旧私有开发历史不在发布范围。依赖与品牌说明见 [NOTICE.md](NOTICE.md)。本项目与米游社及相关游戏官方无隶属关系。
