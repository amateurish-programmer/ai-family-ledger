# AI 家庭账本

面向 Android 的家庭记账 App，采用 Kotlin + Jetpack Compose + Room。V0.6 内置家庭云服务，用户无需填写地址或 key；注册登录后创建或加入家庭即可使用同步与 AI。

[GitHub 仓库](https://github.com/amateurish-programmer/ai-family-ledger) · [APK 构建与下载](https://github.com/amateurish-programmer/ai-family-ledger/actions/workflows/android-ci.yml)

## V0.6.0 功能

- 收入/支出新增、编辑、删除，关机重启后仍由 Room 保存。
- 日期、两级分类、账户、归属成员、记账人、商家、项目、备注。
- 月度流水、月度/年度收支结余与支出分类汇总。
- JSON 备份、恢复预览、重复 ID 跳过与软删除标记保留。
- 随手记 XLSX 导入预览、逐行选择、疑似重复提示、来源追溯和批次撤销；三个工作表导出。
- 一句话简单整理、手机语音转文字、云端 AI 提案；修改并确认后才入账。
- 报告增加成员支出、月度趋势、上期比较、文本导出与可选 AI 解读。
- 内置云端邮箱登录、家庭邀请、显式同步、冲突处理与打开 App 时同步；错误提示区分注册、发信和限流原因。
- GitHub Actions 测试、lint、Debug APK 构建与下载产物。

金额仅支持 CNY，以整数分保存。余额变更保留原值、不参与收支。数据库从 V0.1 非破坏性升级，JSON V2 可读取旧版备份。**Supabase 数据库与 AI 函数已部署，DeepSeek 密钥和 QQ 邮箱 SMTP 已配置；手机、邮件投递和模型请求尚未联调验收**；连接信息见 [云端服务说明](docs/BACKEND_SETUP.md)。语音识别依赖手机安装的识别服务。构建与验收状态见 [验证记录](docs/VALIDATION.md)。

## 不安装 Android Studio，获取 APK

仓库已创建并配置云端构建。打开上方 **APK 构建与下载 → Run workflow**，保留 main 分支并启动。构建成功后打开该次运行，在 Artifacts 下载 `AI-Family-Ledger-debug-运行序号`，解压并安装 `app-debug.apk`。勾选 `build_only` 只编译 APK；`auth_checks` 可追加本次配置与错误提示的针对性检查，`device_tests` 可另行运行模拟器测试。V0.6 构建与七项针对性检查已通过，详见 [V0.6 说明](docs/V0.6.md)。

详细步骤见 [云端构建指南](docs/CLOUD_BUILD.md)。首次安装从空账本开始，不包含用户真实历史数据。

## 工程结构

```text
android/                 Android 工程及 Gradle Wrapper
  app/src/main/          domain、Room、ViewModel 与 Compose 界面
  app/src/test/          金额、报表、备份 JVM 测试
  app/src/androidTest/   Room 持久化、恢复与 UI 测试
supabase/                家庭 RLS/RPC 迁移、AI Edge Function
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

原始 Excel、明文备份、数据库、模型密钥不得进入 Git。模型密钥只配置在服务端，App 有联网权限但不自带后台地址或密钥。同步须主动确认上传范围；默认不开启自动同步。备份含私人账目，卸载前先导出备份。

V0.2 至 V0.5 按原路线连续实现，V0.6 内置现有云服务并改进注册错误提示。手机无需配置连接参数；安装后进入家庭账号与同步页面注册、登录。设备、邮件和云端功能验收状态见验证记录。
