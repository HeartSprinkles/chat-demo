# DeepSeek Chat Demo — Android

基于 deepseek-ai/chat-demo 的非官方原生 Android 移植。Kotlin + Jetpack Compose；不包含 WebView、JavaScript 或应用后端。原网页工程保留在仓库根目录。最低 Android 8.0（API 26）。

## 下载与安装

仓库 Actions → Android CI → 对应成功运行 → Artifacts → `chat-demo-debug`。解压得到 `chat-demo-debug.apk`，在 Android 上允许从下载应用安装未知来源 APK 后安装。`SHA256SUMS` 为该次 APK 校验值。

这是测试版而不是正式签名版本。每次云端构建可能使用不同的调试证书，覆盖安装失败时不要盲目卸载：卸载将清除所有本地会话及密钥，首版尚无历史导出。正式稳定更新通道需要后续配置固定签名。Artifact 保留 14 天，过期后可重新运行 CI。

首次进入设置，填写你自己的 DeepSeek API Key 并验证。Key 仅保存在设备的 Android Keystore AES-GCM 加密存储中；聊天直接请求 `https://api.deepseek.com`，发送的文本与搜索请求会交给该 API 服务处理，可能产生服务费用。仓库和 CI 不需要你的 API Key，不会使用真实服务执行自动测试。联网搜索等扩展是否可用以服务端为准。

## 首版范围

原生多会话、重命名与删除、草稿、流式聊天及停止、深度思考、搜索与来源链接、编辑/重新生成/消息分支、受限继续生成、原生 Markdown、中文/英文、主题与模型设置、本地 Room 存储。

附件、语音、历史 ZIP 导入导出、自动定位、云同步、Mermaid 图形和完整公式排版不在首版范围。后台持续生成不作保证；进程退出后不自动重新发起收费请求。链接交给系统浏览器。

## 构建与测试

所有发布给用户的编译产物通过 `.github/workflows/android.yml` 在 GitHub Actions 的 Ubuntu runner 上构建。使用 JDK 17、Android SDK 35、AGP 8.7.3、Kotlin 2.0.21 和带校验值的 Gradle 8.9 Wrapper。

开发者拥有 Android SDK 时可复现：

```sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

CI 无真实 API 依赖；单元测试与模拟器测试只验证本地功能和模拟协议。真实 API 联调需使用安装者自己的有效 Key。模拟器性能不等于手机真机性能，未测量前不承诺帧率或内存数值。

## 数据与安全

会话及分支保存在应用私有目录中。关闭系统数据备份，禁止 HTTP 明文 API。删除本地会话不代表删除服务端已有数据。密钥失效要求重新输入，不应删除会话。不要将个人 Key、包含隐私的聊天记录或签名材料提交到 Issues。

## 许可证

保留上游 MIT LICENSE。第三方库分别遵循其许可证：AndroidX、Kotlin/kotlinx、OkHttp、Markwon 为 Apache-2.0。这个移植项目与 DeepSeek 官方 Android 客户端无关联，不声称官方出品。
