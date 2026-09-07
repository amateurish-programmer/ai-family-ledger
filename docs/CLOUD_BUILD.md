# 云端构建与手机下载

## 首次上传

当前私有仓库 [amateurish-programmer/ai-family-ledger](https://github.com/amateurish-programmer/ai-family-ledger) 已完成创建、源码上传和构建配置，可直接跳到「下载 APK」。下面步骤供迁移到其他仓库时参考。

1. 登录自己使用的 GitHub 账号，创建私有仓库 `ai-family-ledger`。
2. 把源码包解压后的内容放在仓库根目录。根目录须包含 `android`、`.github`、`docs` 和 `README.md`，不要多套一层目录。
3. 使用 Git 客户端推送，或 GitHub 网页 `Add file → Upload files`。网页上传时需确保隐藏的 `.github` 目录也上传了。不要直接上传整个 ZIP，因为 Actions 不会自动解压。
4. 不上传本地真实 Excel、`.tools`、数据库、个人备份或密钥。源码包用明确白名单生成。

## 下载 APK

1. 仓库 **Actions → Android APK**，首次上传 Android 文件会自动构建，也可 **Run workflow** 手动执行。
2. 等待任务变绿；失败时展开红色步骤查看日志，不把排队或上传源码视为已构建。
3. 在该次运行底部 **Artifacts** 下载 `AI家庭账本-v版本号`（当前 `AI家庭账本-v1.0.0`）。
4. 解压 ZIP，里面的 `AI家庭账本-v1.0.0.apk` 发到 Android 手机安装。
5. 手机按提示允许本次安装来源。首次启动无演示数据，输入一笔收入和支出验证。

Artifact 的网页下载需要登录有权访问该仓库的 GitHub 账号。构建产物保留 14 天，可重新构建。使用自带 Actions 配额；不需要配置付费运行器，具体用量以账号后台为准。

## 验证开关

手动 Run workflow 时可勾选 `device_tests`，额外启动 Android 35 模拟器，验证 Room 跨实例持久化、恢复幂等和录入 UI。设备测试与 APK 构建并行执行；该步骤耗时与 Actions 用量高于普通构建。测试报告保存在 `Android-device-tests-运行序号`，录入截图位于报告的 `diagnostics/ledger-screens` 目录。

`build_only` 只运行 `assembleDebug`，跳过 JVM 测试和 lint；可同时勾选 `auth_checks` 运行全部 JVM 行为测试；V0.10 使用 build_only、auth_checks 和 device_tests 生成 APK 与界面证据。后端协议回归和隔离 PostgreSQL 权限测试始终运行。不能把仅编译成功表述为功能验证通过。

## 签名

Debug APK 使用项目内固定的公开开发签名，便于同包名版本覆盖安装。这个签名仅供开发测试，不能用于正式发布；正式版上线前需独立私钥并放入 GitHub Secrets。若从其他签名构建切换，Android 可能拒绝覆盖安装，此时先备份数据，再处理旧版安装。

## 后端状态

V0.9 已内置公开云服务配置，登录并加入家庭即可对话。已保存账目查看和编辑无需网络。家庭同步/AI 服务源码在 supabase，实际部署与配置见 [BACKEND_SETUP.md](BACKEND_SETUP.md)。不要把 service_role 或模型密钥写入 Android 工程。没有后台配置时，已保存账目编辑、Excel 和确定性报告仍可使用。

## 版本发布约定

每次升级更新 Gradle versionCode/versionName，按明确文件清单签名提交到仓库并触发构建。`scripts/name-apk.py` 从 versionName 自动生成安装包名，不生成 SHA-256 边车文件。正式名称不改变当前开发签名；覆盖安装保留本机数据，不要卸载旧版。


## 应用内更新发布（V0.11）

用户手动安装 V0.11.0 一次后，可在设置页检查更新并下载安装，无需 GitHub 登录。维护者运行工作流时设置 `publish_release=true`；该选项即使选择 build_only 也强制 JVM 与设备检查，所有三类检查成功后才上传 Supabase 更新桶。每个 APK 路径不可覆盖，latest.json 只在远端 APK 完整校验后更新。工作流不会因为普通 push 自动公开发布。签名保持原值，密钥配置和重试规则见 [发布维护](UPDATES_PUBLISH.md)。
