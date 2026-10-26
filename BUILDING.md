# 构建与测试

## 开发环境

- JDK 17+；本项目实际使用 JDK 25 / Gradle 9.1。
- Android SDK Platform 36、Build Tools 36.0.0；最低 Android API 26。
- Gradle Wrapper 版本和 SHA-256 固定在 `gradle/wrapper/gradle-wrapper.properties`。
- 在 `local.properties` 设置 `sdk.dir`，或配置系统 `ANDROID_HOME`。该文件不进入 Git。

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows 可设置 `JAVA_HOME` 后使用 `tools/build-android.ps1`；下载缓存默认留在被忽略的 `.local/`，不使用维护者机器上的路径。

## 测试

45 项 JVM 用例覆盖消息、日期和分页、图片状态机、导出、账号资料及更新清单/安装策略。24 项默认 Android 隔离数据用例覆盖数据库、账号、背景及下载校验；使用独立测试库、偏好或缓存目录，不需要真实聊天数据。

```sh
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

更新测试通过拦截响应验证下载和 URI，不访问任意下载样本站点，不执行安装。一个默认跳过的额外测试需要自行提供正式签名的开发测试 APK，用于核对证书不匹配时的拒绝；不要使用其他人的账号或私钥。

## 提交前的隐私检查

暂存后运行：

```sh
node tools/check-private-files.mjs
node tools/audit-public-source.mjs
node tools/audit-public-source.mjs --history
```

检查输出只含规则和位置。可使用 Gitleaks 对历史进行补充扫描：

```sh
gitleaks git --redact=100 --log-opts=--all .
```

隐私检查器自身的回归测试可用 `node --test tools/test-privacy-audit.mjs` 运行。提交身份请使用 GitHub no-reply 邮箱或不包含个人资料的身份，避免通过提交元数据泄露私人邮箱。

不要提交 `.local/`、签名文件/口令、真实 Cookie/token、聊天记录、设备截图、抓包、日志和个人环境配置。发现已公开凭据时，先撤销或轮换该凭据；删除最新文件不能清除历史中的副本。
