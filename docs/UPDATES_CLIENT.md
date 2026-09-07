# Android 应用更新客户端

设置页手动检查更新，不依赖登录，不读取家庭、账本、认证令牌或模型密钥。更新服务与账本 ViewModel 分开。用户确认下载后显示字节进度；安装前申请系统允许安装未知来源应用，返回应用后重新校验并由用户启动系统安装器。系统安装器仍需要用户确认，不静默安装。

## 公开发布合同

固定清单地址为 `https://xdgeybztysuvvwagqkvb.supabase.co/storage/v1/object/public/app-updates/latest.json`。APK 地址只从相同固定 bucket 与清单中的受限路径派生，每个路径段进行 UTF-8 URL 编码。清单不接受外部下载 URL，不读取任何云端账本。云端对象键使用 ASCII 文件名；本地交付文件继续使用 `AI家庭账本-v{versionName}.apk`。

清单是严格 JSON 对象，字段必须完整且无额外字段、重复字段、尾随内容或数字类型转换：

| 字段 | 要求 |
| --- | --- |
| schemaVersion | 整数 1 |
| versionCode | 1 至 Int.MAX_VALUE 的整数；严格大于本机版本才允许下载/安装 |
| versionName | 三段非负数字，无多余前导零，总长至多 32 字符 |
| minSdk | 大于等于 26，且下载/安装时不得高于设备 API |
| packageName | `com.familyledger.app` |
| apkPath | `releases/{versionCode}/ai-family-ledger-v{versionName}.apk` |
| sizeBytes | 整数 1 至 52,428,800（50 MiB） |
| sha256 | 64 位小写十六进制 |
| notes | 最多 4,000 字符纯文本，允许换行、回车和制表，禁止其他控制字符 |
| publishedAt | ISO Instant 时间字符串，例如 `2026-09-07T00:00:00Z` |

清单最大 32 KiB，仅接受有效 UTF-8。版本比较只使用系统 PackageManager 读取的 versionCode；版本名称只展示并与 APK 交叉核对。HTTP 404 明确提示尚未发布；只有合法清单版本小于等于当前版本时才返回无更新。

## 下载和安装边界

- 固定 HTTPS 地址、不自动跟随 HTTP 重定向、不使用认证或账本设置；连接超时 15 秒、读取超时 20 秒，清单读取总预算 30 秒，安装包读取预算 5 分钟。阻塞读取期间取消可能等待到当前读取结束或读取超时，之后清理临时文件。
- 下载写入应用 `cacheDir/updates/*.part`。检查响应长度（如果提供）、实际长度和 SHA-256，强制大小上限。成功后在同一目录原子移动为 `.apk`，失败或取消删除临时文件与本次目标文件。再次下载只清理更新专用目录内的 `.apk` 和 `.part`。
- APK 下载完成后以及每次用户点击安装前，核对文件路径、长度和 SHA-256；PackageManager 解析 APK 核对包名、versionCode、versionName、minSdk 与清单，拒绝降级、无效归档与不兼容签名。SHA-256 仅用于内部核对，不生成本机边车文件。
- API 26–27 使用 GET_SIGNATURES，要求全部签名集合相同。API 28+ 使用 GET_SIGNING_CERTIFICATES：相同签名集合可用；单签名允许候选包已验证的签名历史包含当前签名，支持向前轮换；多个签名必须完整集合相同。不把旧证书存在于已安装应用历史视为候选旧证书有权回退。
- FileProvider authority 为应用包名加 `.updates`，仅共享更新缓存子目录。安装 Intent 使用 `content://`、APK MIME 和临时只读授权；无写授权，不暴露账本或普通缓存目录。
- cacheDir 可被 Android 清理；找不到文件时需要重新下载。进程退出后不自动恢复下载或自动打开安装器。本模块不修改 Room、认证、同步或账本业务。

官方依据：[PackageManager.getPackageArchiveInfo 与签名查询](https://developer.android.com/reference/android/content/pm/PackageManager)、[SigningInfo 签名集合与轮换历史](https://developer.android.com/reference/android/content/pm/SigningInfo)、[FileProvider 临时 URI 授权](https://developer.android.com/reference/androidx/core/content/FileProvider)。

## 验证状态

新增 JVM 行为测试覆盖严格清单、路径与来源、版本比较、大小/散列错误、取消清理、原子发布结果、签名集合与向前轮换规则。设备测试用 CI 临时生成的同签名高版本 APK 和异签名 APK 验证 PackageManager 实际解析、校验、篡改拒绝、降级/版本不符拒绝、非 APK 拒绝及 FileProvider 路径和只读 Intent。

首次云端链路检查发现中文对象键即使进行百分号编码，Supabase 仍返回 HTTP 400 / InvalidKey。存储合同据此改为上述 ASCII 路径；回归覆盖旧中文存储路径拒绝和完整 ASCII 下载 URL。此次修改不改变本地交付命名或版本号，需重新构建并执行 CI。

本机执行 `gradlew.bat :app:testDebugUnitTest --tests com.familyledger.app.data.AppUpdateTest` 因没有 JAVA_HOME/java 未能启动；源码不等于编译通过。后续统一 CI 记录补充实际构建和测试结果。设备测试只解析合成安装包，不安装，不等于真实手机未知来源授权回流、系统覆盖安装或线上下载验收。
