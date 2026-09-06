# 验证记录

## V0.5.0 连续开发记录

用户最新要求“完成后继续进行后续阶段，无需验证”。本轮仅安排 APK 编译，不执行 JVM 测试、lint、模拟器、真机或云端功能验收。以下 V0.1 通过记录仅适用于旧版，不能作为 V0.5 新功能通过的证据。

已编写 V0.2 导入测试及合成夹具；用户提出不验证之前曾运行一次核心编译，确认导入功能尚缺失。此后只做编译，不执行测试。

- 新增源码：Excel 预览/查重/批次/导出，JSON V2 与 Room V1→V2，文字/语音提案，家庭客户端/云端部署文件，报告趋势/成员/导出/AI入口。
- Android 版本：0.5.0 / versionCode 5，保持包名和开发签名，可覆盖 V0.1。
- 编译成功：https://github.com/amateurish-programmer/ai-family-ledger/actions/runs/33999957197 （build_only=true，设备测试跳过）。
- 构建提交：`95d3211da31f890cfba086dc9ce28e62f2793fcf`；APK：`AI-Family-Ledger-debug-10`。这代表编译完成，不代表新增功能验收通过。
- APK Artifact ID：`9979210047`；已下载到 `dist/apk/app-debug-v0.5.0.apk`，大小 17,614,787 字节。
- 构建生成的 Room V2 schema 已保存到 `android/app/schemas/com.familyledger.app.data.LedgerDatabase/2.json`。
- 2026-09-06 后续部署：Supabase 项目 `xdgeybztysuvvwagqkvb` 已应用迁移 `202609060001`，`ledger-ai` 部署成功；只读取迁移记录、RLS 开关、Auth 公开设置及密钥名称。DeepSeek API Key 已由用户保存；QQ 邮箱 SMTP 授权码由用户填写并保存，刷新控制台确认 SMTP 已开启、Host 为 `smtp.qq.com`、Port 为 `465`、无待保存更改。邮箱投递/账号流程、数据库权限行为、跨设备同步与真实模型请求未验收。详情见 [云端服务说明](BACKEND_SETUP.md)。
- 真实 XLSX 保留在本机，源码包和 Git 使用明确文件清单，不包含原始账本或凭据。

## V0.1.0 历史验证

日期：2026-09-06（北京时间）。

| 验证 | 状态 | 证据或边界 |
|---|---|---|
| 历史工作簿结构检查 | 已执行 | 426 支出 / 31 收入 / 6 余额变更；真实文件只读 |
| 核心 Kotlin 测试红灯 | 已执行 | 9 项在未实现时失败 |
| 核心 Kotlin 测试绿灯 | 已执行 | Kotlin 2.1.20 + Java 17 + JUnit 4.13.2，10 项通过 |
| 审查修复回归 | 已执行 | 缺失删除标记测试先失败后通过；严格 JSON 字段类型；保存事件改由当前界面消费 |
| 独立代码审查 | 已执行 | 两项 P2 已修复并复核；不替代 Android 编译 |
| 配置与源码包检查 | 已执行 | Android XML、Actions YAML 解析；源码 ZIP 完整性；按明确白名单打包 |
| Android 全量编译和 lint | 已通过 | Actions 33976880394；10 项 JVM 测试通过；lint 0 错误、12 提示；APK 上传成功 |
| Room 与 UI 模拟器测试 | 已通过 | 同一 Actions 运行；API 35 模拟器 5 项通过，0 失败、0 跳过 |
| 真机安装、重启、备份恢复 | 未执行 | APK 已生成并下载，仍需手机验收 |
| AI、语音、Excel 导入、家庭云同步 | 未实现 | 后续阶段 |

本地核心验证复用电脑现有 Java，临时 Kotlin 编译器位于被忽略的 `.tools` 中，不修改系统环境。该测试仅编译 domain/BackupCodec，不等于 Android App 编译成功。

## 云端构建证据

- 私有仓库：https://github.com/amateurish-programmer/ai-family-ledger
- 成功构建：https://github.com/amateurish-programmer/ai-family-ledger/actions/runs/33976880394
- APK 所属提交：`08f139276e0ef7fbbb6ebe7c86fdee98955f4570`。
- APK Artifact：`AI-Family-Ledger-debug-9`（ID `9972607578`）；本地交付路径 `dist/apk/app-debug.apk`。
- APK 大小：17,418,043 字节；SHA-256：`18981043413044BCBECEFF050FD0DC123FA2EF81F3A50A00FAC5C1CF9232FF61`。下载后 ZIP 完整性与必需 APK 文件检查通过。
- 首次构建失败原因为 API 26 主题使用了 API 27 的 windowLightNavigationBar。已改为 values-v27 资源覆盖，再次构建通过。
- Lint 不阻断的提示包括较新的依赖版本、目标 Android 版本和 dataExtractionRules 配置建议；本轮不升级整套依赖。
- 构建生成的 Room V1 schema 已保存到 android/app/schemas，供后续迁移使用。

## 模拟器证据与修复

- 4 项数据层测试通过：编辑后的记录跨数据库实例保存、恢复重复 ID 跳过且不复活软删除记录、错误恢复不部分写入、Android JSON 字段类型严格检查。
- 1 项 UI 测试通过：启动真实 MainActivity，点击「记一笔」，输入 36.80 元并保存，确认流水金额，进入报表并检查「支出去向」。
- 首次 UI 等待失败的诊断显示 Activity 为 RESUMED、空账本和按钮已显示，按钮文本被 Material3 的内部语义清除；给加号图标补充「记一笔」无障碍名称，并按内容描述验证点击后通过。
- 模拟器报告：`Android-device-tests-9`；录入截图：`diagnostics/ledger-screens/ledger-recorded.png`，本地副本为 `dist/validation/ledger-recorded.png`。截图使用测试夹具，不包含真实历史账目。
- 模拟器 API 35 通过不代表 API 26 真机已验收；文件选择器、设备重启、旋转和大字体仍按下面清单在手机上验证。

## 手机验收清单

- [ ] 首次空账本；保存一笔 36.80 元支出和 100.00 元收入，结余 63.20 元。
- [ ] 修改支出为 40.00 元；退后台/终止/重开后保留；结余为 60.00 元。
- [ ] 月份切换不混入相邻月份；年度统计可包含跨月数据。
- [ ] 错误金额与不存在日期不能保存；保存按钮防重复点击。
- [ ] 删除需确认；报表不再统计删除项。
- [ ] 导出 JSON，恢复相同文件不重复；旧备份不复活已删除记录。
- [ ] 恢复错误文件不改变当前账本；取消文件选择安全返回。
- [ ] 大字体、窄屏、键盘弹出和旋转时检查录入可用性。
