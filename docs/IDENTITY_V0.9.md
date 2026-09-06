# V0.9 身份与家庭资料

## 用户流程

登录页新增“忘记密码”：填写原邮箱，发送重置验证码，输入邮件验证码、新密码和确认密码。服务端确认发送成功后，页面才开始 60 秒重发间隔；服务端限流仍为最终依据。验证码错误、使用过或过期会提示重新获取。密码成功更新后清空敏感输入并返回登录，邮箱保留供登录使用。密码与验证码只存在当前内存输入和请求中，不使用 rememberSaveable、偏好存储或日志。

密码恢复使用 `POST /auth/v1/recover` → `POST /auth/v1/verify`（email、token、type=recovery）→ 使用恢复 access token 调用 `PUT /auth/v1/user`（password）。恢复逻辑不调用 acceptSession，不保存 access/refresh token，不切换本机账号、家庭绑定或同步索引。验证码返回的用户邮箱必须与所填邮箱一致；完成或更新失败后均尽力调用 `POST /auth/v1/logout?scope=local` 撤销临时会话，撤销失败不推翻已确认的密码更新。网络中断不能保证远端立即撤销。

密码更新响应丢失时无法判断服务器是否已修改，页面提示先尝试新密码登录；如仍需重置，必须重新获取验证码，因为旧验证码可能已消费。原本永久绑定的账号限制仍有效，恢复其他邮箱不会解除该限制。

## 家庭与本机角色

家庭名称与家庭图标为家庭共享资料，创建者可以编辑，其他成员只读。图标固定为 home/heart/tree/sun，名称为 1～80 个 Unicode 字符，不允许控制字符。修改不会变更家庭 ID、owner、成员关系或账目。家人在登录、刷新家庭资料或同步时获取更新；本版没有实时推送。

角色名和角色头像是本机显示设置，头像固定为 person/man/woman/child/elder/cat。本机角色与头像按已绑定账号（否则当前账号，否则 local）分组保存；已绑定账号退出后仍使用同一组。角色、头像均不作为鉴权依据。`setLocalIdentity` 在一次 Preferences commit 中保存角色和头像。`conversationOwner()` 仅返回该分组标识，不提供 token。

## 云端合同与部署前提

新增迁移 `202609060003_family_profile.sql`，不改写已应用迁移，不改变 Room 版本。families 新增 icon，默认 home；保留原有 RLS 和直接写入禁令。

| RPC | 输入 | 输出与权限 |
| --- | --- | --- |
| get_my_family | {} | null 或 {id,name,icon,is_owner}，兼容原有 id/name；仅当前认证用户 |
| update_family_profile | {p_name,p_icon} | {id,name,icon,is_owner}；服务器以 auth.uid() 同时校验 owner 与成员关系，锁定家庭后更新 |

旧版 create_family 返回仍保留 id/name；客户端创建后再次读取 get_my_family 获取新字段。新客户端对缺少 icon/is_owner 的响应默认 home/false，缺少服务器权限信息时不会显示资料写入按钮。两个 RPC 均固定空 search_path，撤销 PUBLIC/anon 执行权，仅 authenticated 可调用。

Supabase Recovery 邮件模板必须展示 `{{ .Token }}`，仅有恢复链接的旧模板不能完成 App 内验证码流程。模板配置和部署结果由主交付记录单独确认；本文件不把源码完成视为云端配置已生效。官方依据：[Auth REST recovery/verify/user](https://github.com/supabase/auth/blob/master/README.md)、[邮件模板 Token](https://supabase.com/docs/guides/auth/auth-email-templates)、[当前会话退出](https://supabase.com/docs/guides/auth/signout)。

## 验证边界

新增 `IdentityV09Test` 九项 JVM 行为测试：恢复请求合同、临时 token 更新/撤销、错误验证码与重试、更新失败与撤销、撤销失败后的成功结果、恢复邮箱错配、网络前输入校验、固定资料校验和安全错误提示。使用合成账号与请求替身，不发送邮件，不使用生产凭据。

新增 `supabase/tests/family_profile.sql`，在独立 Supabase 测试数据库中以合成用户验证创建者更新、成员只读、跨家庭隔离、匿名拒绝、直接 UPDATE 拒绝、默认图标、输入校验和重复请求，全部夹具位于事务并回滚。该脚本不是生产迁移。

本机执行 JVM 测试时因 JAVA_HOME 未设置且 PATH 无 java 而停止，未进入编译或测试断言。云端构建/测试、SQL 行为执行、邮件实际投递、真机重置、跨设备资料刷新与 Keystore 绑定保留的验收状态应由主交付记录补充，不能由静态检查替代。

最终 CI 34039378934 已通过全部 66 项 JVM 测试（包含本文件九项）及隔离 PostgreSQL 权限检查。003 迁移已通过官方 CLI 应用到云端，用户确认验证码邮件模板已保存；真实邮件完整重置仍未代用户执行。
