# 签名、发布与更新

## 版本

`release.properties` 管理内部版本号、显示版本和 GitHub 更新仓库。正式发布使用 `X.Y.Z` 和 `vX.Y.Z` 标签，每次必须递增 `versionCode`。开发版使用 `-dev` 后缀。客户端只检查最新公开稳定 Release；未发布、草稿和预发布不会被视为正式更新。

Fork 后应修改 `release.repository` 为自己的 `owner/repository`；发布工作流会显式使用当前 `GITHUB_REPOSITORY`。不要将 GitHub token 放进客户端，更新仓库应允许公开读取。

## 长期签名

复制 `signing.properties.example` 为本地 `signing.properties` 并填写自己的密钥信息；或在 Windows 设置 JDK 后运行一次：

```powershell
.\tools\init-release-signing.ps1
```

脚本拒绝覆盖现有密钥，生成的密钥和口令不进入 Git。请安全备份并长期使用同一把密钥；丢失密钥会影响同包名覆盖更新。调试签名与正式签名不能直接互相覆盖，不要为切换版本贸然卸载包含重要本地数据的应用。

GitHub Actions Repository Secrets：

| 名称 | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 自己的 PKCS12 密钥文件的 Base64 |
| `ANDROID_STORE_PASSWORD` | 密钥库口令 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 私钥口令 |

不要在 Issues、日志或 Release 附件中粘贴上述内容。正式构建缺少签名或仍为开发版本名时会停止。

## 构建发布附件

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease -Prelease.versionName=0.3.2
node tools/package-release.mjs
```

确保版本号适合本次发布。`artifacts/release/` 生成一个通用 APK、`update.json` 和 `SHA256SUMS`，三者应放入同一 Release；不要手工编辑清单。用 Android SDK 的 `apksigner verify` 检查最终签名。

推送稳定版本标签会触发 `Prepare Android release`；也可以手动选择一个已存在的标签。工作流测试、签名并校验后仅创建草稿，拒绝覆盖公开 Release。检查附件和说明后再发布。发布前必须确认内部版本号高于上次公开版本。

首次正式发布前应验证实际 Release 下载和系统确认安装链路。客户端会核对 SHA-256、长度、包名、版本、系统兼容性和证书，不静默安装；签名不同会阻止覆盖。浏览器发行页作为网络受限时的备用入口。
