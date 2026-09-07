# 应用更新发布

设置页使用公开 Supabase Storage 更新清单。发布只包含应用版本、更新说明、安装包路径、大小、校验值及发布时间，不包含家庭账本、账号或密钥。当前包名与开发签名保持一致；正式更换签名仍需单独制定迁移方案。

## 发布入口和验证门槛

在本仓库 `main` 手动执行 **Android APK** 工作流，勾选 `publish_release`。该选项默认关闭。启用后始终运行全部 JVM 测试、后端回归和模拟器测试；即使勾选 `build_only`，也会补跑 JVM 测试。只有 build、backend-tests、device-tests 全部成功才进入发布。`build_only` 仍跳过 lint，报告应明确区分。普通 push、PR、其他分支和其他仓库不发布。

构建产物名来自 Gradle 的 `versionName`，发布任务只下载当前运行对应的一个版本 APK。发布脚本再次要求目录内恰有一个 APK 且名称与 Gradle 版本一致。版本号和包名来源 `android/app/build.gradle.kts`；本版简短说明在 `docs/update-notes.txt`。升级应同步更新这两个来源。

发布任务以 `publish-app-updates` 独立并发组串行执行，且不取消正在运行的发布；外层工作流也防止后续构建取消发布运行。GitHub 并发组不承诺排队顺序，脚本拒绝低版本覆盖高版本。不要在 CI 外另行并发写更新对象。

## 服务配置

公开项目 URL 为 `https://xdgeybztysuvvwagqkvb.supabase.co`。预先建立 `app-updates` 公共 bucket，最大对象大小 **50 MiB（52,428,800 字节）**，允许 `application/vnd.android.package-archive` 与 `application/json`。公共读取不需要 service key；客户端不能写入。bucket 的创建和权限配置是独立云端操作，不由发布脚本自动变更。

GitHub Actions Secret `SUPABASE_UPDATE_SERVICE_KEY` 提供服务端上传凭据，仅在 publish 步骤通过环境变量注入。脚本不会将它写入磁盘、命令参数、清单、APK或日志。不得把该 key 放入客户端。脚本不输出远端错误正文和异常链，HTTP 错误仅输出状态码。

## 公开清单合同

`/storage/v1/object/public/app-updates/latest.json` 是 UTF-8 JSON，字段固定如下：

| 字段 | 约束 |
|---|---|
| schemaVersion | 整数 1 |
| versionCode | 正整数，Android 可接受范围内，递增 |
| versionName | 三段 ASCII 数字，各段无前导零（单独 `0` 合法），总长最多 32 字符，如 `0.11.0` |
| minSdk | 整数，至少 26 |
| packageName | `com.familyledger.app` |
| apkPath | `releases/{versionCode}/AI家庭账本-v{versionName}.apk` |
| sizeBytes | 1 至 52,428,800 |
| sha256 | APK 实际字节的 64 位小写十六进制 SHA-256 |
| notes | 最多 4000 个 UTF-16 单元（与 Android 一致）的公开更新说明；禁止 ISO 控制字符，换行、回车、制表符除外 |
| publishedAt | UTC ISO 时间，末尾 `Z` |

SHA-256 仅进入更新清单，不生成本机边车文件。清单最大 32 KiB，仅接受 UTF-8，拒绝重复 JSON 字段；解析入口独立校验字节大小，不仅依赖网络层限制。APK读取分块并限制总大小，网络单次超时 60 秒，拒绝所有 HTTP 重定向。读回使用公共下载端点并附加随机查询参数，避免将已有缓存误作刚上传的内容。

## 发布事务与重试

1. 校验本地 Gradle、唯一 APK、ZIP 完整性和说明，计算清单。
2. 读取现有清单，格式错误立即失败；首次发布只接受明确的 Storage 对象缺失，不将代理 404、bucket 缺失或权限错误误认为首次发布。
3. APK 上传使用 `POST`、正确 APK Content-Type 和 `x-upsert: false`。同路径已有不同字节时拒绝覆盖；已有相同字节可继续重试。
4. 完整下载远端 APK，实际比较大小与 SHA-256；通过后再检查 latest 没有改变。
5. 使用 `x-upsert: true` 与 `Cache-Control: max-age=0` 更新 latest，读回并核对全部字段。

latest 只允许升版本；同版本必须全部元数据一致（保留原发布时间）且远端 APK 通过校验才视为幂等成功，不重写对象。上传成功但客户端丢失响应时，会读回不可变对象判断是否可继续。APK成功而latest失败时保留版本对象，下一次同内容运行可补完；不要删除已发布历史对象。latest上传结果不确定时，下次相同输入也可安全重试。

Storage 不提供这里所用对象API的跨对象事务，正确性依赖 CI 串行写入。脚本在最后写清单前重读指针检测外部写入；管理员仍不得绕过该串行入口并发发布。

## 验证边界

本地运行 `python -m unittest discover -s scripts -p test_publish_app_update.py`，使用临时合成 APK 与内存 Storage，并在网络传输层模拟 HTTP 错误，不读取生产配置或写生产。覆盖首次发布、发布顺序、重复运行、冲突、防降版、损坏读回、合同字段、唯一产物、大小上限、去敏、缓存和重定向限制。

本地测试不能替代 GitHub Secrets、真实 Storage权限、CDN读取、手机安装和覆盖保留数据的验收。实际云端发布结果须附对应运行记录，不能将本脚本验证通过等同于已发布。

Storage缺失错误处理依据[官方错误码说明](https://supabase.com/docs/guides/storage/debugging/error-codes)；客户端可能收到HTTP400包裹的对象404，因此同时校验HTTP状态与结构化错误内容。
