# AI 家庭账本

面向 Android 的家庭记账 App，采用 Kotlin + Jetpack Compose + Room。先交付可独立使用的本地账本，再接入历史 Excel、AI 语音和家庭云同步。

## V0.1.0 已实现的源码功能

- 收入/支出新增、编辑、删除，关机重启后仍由 Room 保存。
- 日期、两级分类、账户、归属成员、记账人、商家、项目、备注。
- 月度流水、月度/年度收支结余与支出分类汇总。
- JSON 备份、恢复预览、重复 ID 跳过与软删除标记保留。
- GitHub Actions 测试、lint、Debug APK 构建与下载产物。

当前为本机账本，尚未提供登录、家庭同步、Excel 导入、大模型和语音输入。金额仅支持 CNY，以整数分保存。正式构建与设备验收状态见 [验证记录](docs/VALIDATION.md)。

## 不安装 Android Studio，获取 APK

将本工程源码上传到自己的 GitHub 仓库，打开 **Actions → Android APK → Run workflow**。构建成功后下载 `AI-Family-Ledger-debug-运行序号`，解压并安装 `app-debug.apk`。

详细步骤见 [云端构建指南](docs/CLOUD_BUILD.md)。首次安装从空账本开始，不包含用户真实历史数据。

## 工程结构

```text
android/                 Android 工程及 Gradle Wrapper
  app/src/main/          domain、Room、ViewModel 与 Compose 界面
  app/src/test/          金额、报表、备份 JVM 测试
  app/src/androidTest/   Room 持久化、恢复与 UI 测试
.github/workflows/       云端 APK 构建与可选模拟器测试
docs/                    阶段设计、表格映射、构建与验收
scripts/                 本地辅助验证和源码打包
```

## 开发与验证

JDK 17 + Android SDK 35 环境下：

```sh
cd android
bash ./gradlew testDebugUnitTest lintDebug assembleDebug
# 有模拟器/设备时
bash ./gradlew connectedDebugAndroidTest
```

Gradle 8.11.1 使用官方 wrapper，并校验发行包 SHA-256。AGP 与 JDK/Gradle 的兼容依据见 [Android 官方说明](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。

## 数据与后续阶段

原始 Excel、明文备份、数据库、模型密钥不得进入 Git。当前没有联网权限或模型密钥。备份含私人账目，请自行妥善保存。卸载前先导出备份。

1. V0.2：随手记工作簿导入/导出、预览查重、批次追溯。
2. V0.3：文字/语音解析、可编辑确认卡、服务端模型代理。
3. V0.4：Supabase Auth、家庭邀请、RLS、离线同步和冲突处理。
4. V0.5：完整月报/年报、成员趋势与 AI 解读。
