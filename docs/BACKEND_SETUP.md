# 家庭云同步与 AI 服务配置

2026-09-06 已部署到用户的 Supabase 项目“记账工具”：数据库迁移 `202609060001` 已应用，五张业务/私有表启用 RLS，`ledger-ai` Edge Function 已上线。模型配置为 DeepSeek `deepseek-v4-flash`，API Key 由用户在 Supabase Secrets 中保存，部署工具只确认名称存在，未读取密钥值。未进行手机、多账号同步或真实模型请求验收。Android 客户端已随 V0.5 APK 编译成功。


V0.9 更新（2026-09-06）：迁移 `202609060003_family_profile.sql` 已部署，家庭创建者可修改名称及固定图标；`get_my_family` 增加 icon/is_owner，新增 `update_family_profile`。`ledger-ai` 已部署日周报告日期支持。用户已确认 Reset Password 模板保存验证码 `{{ .Token }}`；App 通过独立 recovery 会话重置，不改变本机账号绑定。实际邮件重置与跨设备刷新未实测，详见 [V0.9](V0.9.md) 和 [身份合同](IDENTITY_V0.9.md)。

## 当前项目与内置配置

V0.7 已更新 `ledger-ai`，增加 `chat` 操作并兼容旧版 `parse`/`report`。输入为 `{text,today,role,history,members,categories}`，历史最多四条、成员/分类各最多四十项，总请求仍限 32 KiB；返回的 result 字符串包含 `{reply,entries,query}`。query 为 `{start,end,member,category,keyword}`，end 不包含。服务端不查询账目、也不执行账目改删，金额汇总由 Android 本机完成。普通 Preferences 新增按账号保存的本机角色，角色不授予任何权限。详见 [V0.7](V0.7.md)。

注册后续状态：用户更新 QQ 授权码后已收到邮件；控制台确认受影响账号已验证。默认 Site URL 已从 localhost 改为本项目 `auth-result` 指引端点，详见 [跳转修复](AUTH_REDIRECT_FIX.md)。下方 SMTP 失败与初始验收描述保留为部署历史。

- 项目根 URL：`https://xdgeybztysuvvwagqkvb.supabase.co`
- Android 公开 key：`sb_publishable_DMkKHBxMWwQ-j-hWvj-cuw_pE8R4NH-`
- [项目控制台](https://supabase.com/dashboard/project/xdgeybztysuvvwagqkvb)，区域为用户创建时选定的 East US (Ohio)。
- [AI 函数](https://supabase.com/dashboard/project/xdgeybztysuvvwagqkvb/functions)，模型地址 `https://api.deepseek.com`，通过服务端访问；手机不填写 DeepSeek 密钥。
- V0.6 已在 `CloudEndpoint.kt` 内置上面两项公开参数，普通用户无需配置。App → 设置 → 家庭账号与同步：使用独立的 App 邮箱账号注册或登录。Supabase 控制台的 GitHub 登录不等于 App 登录。V0.5 同项目配置自动接续；其他项目已有身份或同步索引时拒绝迁移，保留本机数据。
- 当前邮箱密码注册已开启，要求邮箱验证；QQ 邮箱自定义 SMTP 已由用户填入授权码并保存。刷新后确认 SMTP 开关开启、服务器 `smtp.qq.com`、SSL 端口 `465`、发件人名称“AI 家庭账本”，保存按钮为禁用状态（无待保存更改）。未读取授权码，未发送测试邮件，实际投递未验收。自定义 SMTP 用于家庭成员注册验证邮件，见[官方说明](https://supabase.com/docs/guides/auth/auth-smtp)。
- 登录后创建家庭；其他成员使用各自账号登录，再输入家庭创建者生成的一次性邀请码。

上述公开 key 用于标识项目，权限由用户登录和 RLS 决定；它不是 service_role 或 DeepSeek 密钥。

2026-09-06 注册故障修正：Auth 日志记录 QQ SMTP `535 Login fail`；发现 SMTP Username 被重复拼接为两遍邮箱。已改回单个邮箱并保存，刷新页面确认持久生效；原授权码保留。仍未代用户发起注册邮件，实际投递需重试确认，详见 [V0.6 记录](V0.6.md)。

## 部署步骤

1. 在自己的 Supabase 账户建立独立项目，启用 Email/Password Auth，并按需要保留邮箱验证。客户端注册后须按邮件完成验证；配置邮件发送与 Auth 限流。
2. 使用 Supabase SQL Editor 执行 `supabase/migrations/202609060001_family_cloud.sql`，或者通过 Supabase CLI 关联自己的项目并执行数据库迁移。脚本按首次新建表设计；后续修改应新增迁移，不能删表重建生产账本。
3. 通过 Dashboard 的 Edge Function Secrets 或自己的本地安全环境配置 `OPENAI_API_KEY`、`OPENAI_MODEL`，可选 `OPENAI_BASE_URL`（默认 `https://api.openai.com/v1`，必须是支持 Chat Completions / JSON object 输出的 HTTPS 服务根路径）。例如供应商根地址为 `https://api.example.com/v1`，函数自行追加 `/chat/completions`。不要把这些值写入仓库或 Android。Supabase 自动提供 `SUPABASE_URL`、`SUPABASE_ANON_KEY`、`SUPABASE_SERVICE_ROLE_KEY`。
4. 部署 `supabase/functions/ledger-ai`。`supabase/config.toml` 对该函数配置 `verify_jwt=false`，函数内部仍强制向 Auth 服务验证用户，并检查家庭成员关系，不能删除此校验。无需把 service_role 配置到手机。
5. 维护者在 `CloudEndpoint.kt` 配置项目根 URL 与公开 key 后构建 APK；V0.6 已内置当前项目，用户界面不提供配置输入。只允许 `https://项目标识.supabase.co`，暂不支持自定义域、localhost、自托管、显式端口、路径或重定向。首次登录后创建家庭，家庭创建者生成邀请码，其他账号登录后输入邀请码加入。

官方配置参考：[Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security)、[Edge Function Auth](https://supabase.com/docs/guides/functions/auth)、[DeepSeek 思考模式](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode/)。函数仅对官方 `api.deepseek.com` 设置 `thinking.type=disabled`，继续要求 JSON object 输出；其他兼容供应商不附加该参数。当前部署状态以上节为准。

## 数据、身份与权限

- `families`、`family_members`、`ledger_entries` 均启用 RLS。客户端仅可读取自己的家庭；所有写操作通过明确授权的安全 RPC。`ledger_private` 保存邀请哈希和 AI 配额，不暴露账目写权限。
- `ledger_entries` 以 `(family_id,id)` 为主键，`payload` 是完整的 `BackupCodec.encode(listOf(entry))` V2 文本，保留整数分、软删除、导入来源；服务端验证单条 UUID、格式、金额范围、日期、必填字段、类型、来源和大小。`revision` 从服务端递增，`actor` 强制为 `auth.uid()`；业务上的成员/记账人标签不作为权限身份。
- 同步只在用户明确选择后上传本机账目及已导入的来源字段，不读取或上传原始 XLSX 文件。完整导入来源可能含文件名、原始列值，因此同步确认范围包括这些信息。AI 不读取 XLSX，也不会自动读取所有明细；解析发送用户输入文字，报告仅发送客户端已经汇总的统计。
- 一个 Auth 账号在本版本只能属于一个家庭；创建或加入成功后，客户端永久绑定项目、账号与家庭。再次登录其他账号或切到其他家庭会被拒绝，退出不会解除绑定。更换家庭必须先导出完整备份并使用独立应用数据；不要将旧家庭备份导入另一个家庭后误点同步。服务端 `get_my_family` 可恢复因创建/加入响应丢失而未写到手机的家庭归属。
- 登录后尚未读到成员关系，或创建/加入请求发出后响应丢失时，客户端保存“待确认归属账号”锁，退出也不清除。必须用同账号重新登录并恢复归属，防止超时请求在服务端成功后换账号误传；创建/加入失败可用同账号重新尝试。仅一次普通登录确认无家庭、且从未发出创建/加入请求时可以解除待确认锁。
- access/refresh token、账号邮箱和家庭绑定使用 Android Keystore AES-256-GCM 加密。普通 Preferences 只保存项目 URL、公开 key、同步 revision/hash 和用户自动同步开关。密钥不可导出；密文不可解密时云操作失败关闭，不能重置绑定后继续上传。
- 退出立即移除本机 token，并在 IO 后台尝试撤销当前服务端会话；断网时无法保证远端立即撤销。退出仍保留本机账本、家庭绑定和同步索引。设备数据/应用备份必须保持禁用或排除云凭据，不能跨设备迁移 Keystore 密文。

## RPC 合同

| RPC | 请求 | 返回/限制 |
|---|---|---|
| `get_my_family` | `{}` | `null` 或 `{id,name}`；仅当前认证用户 |
| `create_family` | `{p_name}` | `{id,name}`；网络重试恢复已有家庭 |
| `create_invite` | `{}` | 64 位十六进制随机码；仅 owner，24 小时到期，一次性消费，最多十个有效待用邀请，仅 SHA-256 入库 |
| `join_family` | `{p_code}` | `{id,name}`；原子消费邀请，已有家庭原样返回，不能通过此接口换家庭 |
| `put_ledger_entry` | `{p_family,p_id,p_payload,p_expected_revision}` | 成功 `{ok:true,revision}`；冲突 `{ok:false,revision,payload}`；0 表示只允许新增 |
| `get_ledger_manifest` | `{p_family,p_after}` | 同家庭 id/revision/payload_bytes，UUID 游标，每页五百条 |
| `put_ledger_entries` | `{p_family,p_entries}` | 最多五十条、6 MiB，逐条返回 id/ok/revision；复用单笔权限/CAS |
| `consume_ai_quota` | `{p_user}` | boolean；仅 service_role 可执行，仅用于已验证用户的 AI 配额 |

全部函数固定空 `search_path`，显式指定表与自定义函数 schema，撤销默认公开执行权；只向 authenticated 授予必要 RPC，向 service_role 单独授予配额 RPC。客户端无直接 INSERT/UPDATE/DELETE 授权，不能改成员、邀请、actor 或 revision。

## 同步与冲突

Android `CloudService` 使用 `HttpURLConnection`、`org.json` 和 IO 协程，无新增网络 SDK。手动同步先检查当前成员关系，按 UUID 游标每页五百条读取轻量版本清单，只下载变化记录，并按最多五十条和字节上限分批 CAS 上传；软删除必须一同上传下载，不能物理过滤掉删除项。单条 payload 上限 768 KiB，整个同步快照限 10 MiB / 十万条，HTTP 响应限 12 MiB；超限停止并明确报错。

同步索引只保存上次成功同步的服务端 revision 与规范 JSON 的 SHA-256。两边内容相同更新索引；只有远端变更则落地；只有本机变更则按 revision CAS 上传；两边都变更或从未同步过的同 ID 内容不同则保留双方冲突；若两边都已软删除同一 ID，则按删除时间、更新时间和规范哈希自动收敛到一个墓碑，不为两个删除结果制造人工冲突。下载只有 `applyRemote` 持久化成功后记索引，上传只在服务端确认后记索引，失败不清索引。网络写成功但响应丢失可在下次同步由内容相同恢复；已同步记录从服务端消失会停止，避免静默重建。

冲突解决必须用户明确选择本机或云端；选择前再读取最新 revision，已变化则要求重新同步，不覆盖新修改。调用方在同步/解决冲突期间须锁住本机编辑、导入、恢复等写入，保证传入 local 快照有效。每次同步不是跨全家庭的全量数据库快照，也不是跨网络的事务：并发设备刚新增或再次编辑的记录可能到下次同步再出现；出错前已成功的批次操作保留，未完成操作可重试。

默认不自动同步。用户明确开启“打开 App 时同步”后由应用前台生命周期触发；这不是后台常驻、定时服务或实时订阅。关闭开关不删除已上传数据。

## AI 接口

`POST /functions/v1/ledger-ai`，携带有效用户 `Authorization: Bearer ...` 与公开 `apikey`，JSON 请求 `{operation,input}`。成功统一返回 `{result:string}`，失败返回适当 HTTP 状态和简短 `{error}`，不返回上游原始错误/账目内容，不记录请求或供应商响应日志。

- `parse` 输入 `{text,today}`，`today` 为 `YYYY-MM-DD`；结果 `result` 是 JSON 字符串，严格结构为 `{"entries":[{"type":"EXPENSE","amount":"36.80","date":"2026-09-06","category":"食品酒水","subcategory":"午餐","account":"银行卡","member":"本人","recordedBy":"本人","merchant":"","project":"","note":"原文"}]}`。最多二十条、所有字段为文本，type 仅收入/支出，金额为十进制元字符串。模型只生成提案，客户端仍做金额/日期校验并经用户编辑确认才入账。
- `report` 输入 `{period,income,expense,balance,categories:[{name,amount}],members:[{name,amount}],trend:[{period,income,expense}]}`；所有金额必须为客户端以整数分计算后格式化的字符串。函数不合计报告金额；模型以定性解释为主，可引用原始汇总数值。2026-09-06 已修复“任意数字触发 502”：允许输入中的年月、行首序号与准确金额；未提供数值、百分比、无效格式或输出截断时明确返回基础摘要。认证、额度和网络故障仍报错。新增固定诊断事件，不记录请求或模型正文。详见 [报告修复记录](REPORT_AI_FIX.md)。
- `chat` 新增可选 `finance` 摘要；支持真实统计分析和八条历史上下文，保持旧版兼容。`analyze` 输入 `{text,history,finance}`，返回财务分析纯文本。事实引用由程序展开。详见 [V0.8](V0.8.md)。
- 请求限制 64 KiB；`parse` 原文最多六千字符，App 使用的 `chat` 原文最多两千字符。明确的“今天…收入…元”在模型两次输出协议失败后可由服务端严格提取原文金额并返回待确认提案；新客户端还会在云端失败时对可严格解析的简单收支使用本机待确认回退，均不会自动保存。供应商调用超时二十五秒，parse/report 最大输出 2500 tokens，chat 最大 4000 tokens，响应上限 128 KiB；不自动重试供应商以免重复计费。Supabase Auth/数据库请求各限十秒；所有 fetch 禁止重定向。
- 数据库原子限流：单用户每分钟五次、每天三十次，整个项目每分钟三十次、每天三百次。按 UTC 分钟/日期计数，包括失败尝试；已到全局限制的请求不调用供应商。应另外在供应商控制台设置预算和告警，服务端限流不等于供应商金额账单保证。额度策略属于代码配置，不能由手机覆盖。

## 待验收边界

本轮按用户后续授权完成数据库和函数部署，保留“不运行功能验收”的要求。初次迁移因 PL/pgSQL 条件中的 CASE 表达式缺少括号而失败，确认事务回滚后修正三处同类写法，再次迁移成功并记录版本。读取表元数据确认 RLS 开启不代表权限行为验收通过。仍未验收成员/非成员/匿名访问、邀请并发消费与过期、CAS 同时编辑与删除、离线重试、登录刷新/退出、跨家庭拒绝、应用重启后 Keystore 解密、设备前台自动同步、AI 配额及真实模型请求。没有实测结果的项目不能宣称已通过。

V0.8 同步批处理实现与验证更新见 [同步性能记录](SYNC_PERFORMANCE.md)，原有未验收边界未自动转为已通过。


## V1.2 人情往来（2026-09-07）

已部署并登记202609070001_gift_metadata迁移，保留旧客户端写入时省略的人情字段，原有家庭权限和CAS仍有效。ledger-ai已部署giftFields显式能力扩展与gift_history操作；模型服务配置沿用服务端已有值，无需手机配置。发布前隔离PostgreSQL权限/兼容测试与AI合成协议检查通过，生产迁移版本与触发器已回读确认；真实账本姓名提取效果由用户核对，不自动保存AI提案。详见[V1.2](V1.2.md)。
