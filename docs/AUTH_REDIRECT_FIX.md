# 邮箱验证跳转修复（2026-09-06）

用户已收到注册邮件，但点击后打开 `http://localhost:3000` 并提示连接被拒绝。控制台 Site URL 仍为该开发默认值；客户端注册请求没有指定其他跳转地址。

本次在控制台确认受影响账号的 Confirmed at 为当天 11:18（北京时间），所以该账号已完成验证，可直接返回现有 Android App 使用邮箱和密码登录。再次打开一次性链接出现 `otp_expired` 不能单独证明账号未验证。本文不保存邮箱、用户 ID、完整验证链接或令牌。

## 部署变更

- 新增 `supabase/functions/auth-result/index.ts`，通过 `--use-api` 部署到现有项目。
- Site URL 改为 `https://xdgeybztysuvvwagqkvb.supabase.co/functions/v1/auth-result`；保存并重新加载控制台后确认持久保存，Save changes 按钮禁用。
- 该公开端点仅提供中文登录指引，无数据库、认证、密钥访问，也不会执行验证或修改账号。只有 GET/HEAD 可用。
- 默认 Supabase 域名不提供 HTML 网页托管，因此使用原生 UTF-8 纯文本，兼容手机和电脑浏览器。没有第三方资源、脚本、跳转或请求内容日志。
- 验证结果可能位于 URL fragment，服务器不能读取，因此页面不宣称验证成功，以 App 登录结果为准。不要把完整验证 URL 发到聊天或工单。
- 邮箱验证保持启用。现有 App 无需升级。已发出的旧邮件不重写，后续注册邮件使用新默认地址。

## 检查与边界

新端点无凭据 GET 返回 HTTP 200、`text/plain; charset=utf-8`、`Cache-Control: no-store` 和正确中文指引。云端账号已确认状态来自控制台，未通过管理员强制确认。未创建测试用户、发送测试邮件或使用用户密码登录；新邮件点击和设备登录仍需用户实际操作。

参考：[Supabase 跳转地址](https://supabase.com/docs/guides/auth/redirect-urls)、[邮件模板](https://supabase.com/docs/guides/auth/auth-email-templates)、[Edge Functions 内容类型限制](https://supabase.com/docs/guides/functions/limits)。
