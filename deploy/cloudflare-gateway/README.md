# 家庭账本云入口

`https://family-ledger-gateway.pages.dev` 使用 Cloudflare Pages Functions，固定转发到原 Supabase 项目 `xdgeybztysuvvwagqkvb`。沿用宝贝成长星球的免费部署账号，没有购买域名或开通付费套餐。账号、账本、RLS 和 Auth 留在原项目。

API 沿用客户端实际使用的 Auth、指定 RPC、受限账目查询和 ledger-ai 路径；`/updates/` 映射原 app-updates 桶。只转发必要的 Authorization、公开 apikey 和 Content-Type，下载不带账号凭据。禁止任意上游、管理接口、浏览器跨域、重定向、缓存及写请求重试。无服务密钥、数据库绑定、持久化或请求正文日志。

请求最多 6 MiB、API 回复 12 MiB、清单 32 KiB、APK 50 MiB。API 完整受限读取并核对已知长度，保持原始 JSON 字节及整数金额；APK 流式传输，Android 继续检查完整性与签名。普通 API 25 秒预算，AI 115 秒；客户端保留原超时。

## 测试与部署

```powershell
npm ci
npm test
npm run test:runtime
npm run prepare:pages
cd pages
wrangler pages deploy --project-name family-ledger-gateway --branch main --commit-dirty=true
```

账号 `80ad827d555178e2c8af9a93c59beb2d`，Direct Upload 项目 `family-ledger-gateway`，生产分支 main。Wrangler 通过本机 OAuth 使用 account:read、pages:write 和用户/离线登录权限，凭据不上传。唯一实现 `worker.mjs` 复制为 Pages `_worker.js`；忽略生成目录。

APK 仍由原 GitHub Actions 发布到 Supabase，原工具验证成功后更新清单及清理旧包。新发布无需重部署入口。免费额度或网络失败时，本机记录继续保留。首次升级方式和验收边界见 [V1.9.2](../../docs/V1.9.2.md)。

V1.9.3 在 Wi-Fi 使用此入口；纯移动网络保留原 Supabase 直连。选择只影响新请求目标，不改变账号、项目身份或服务器权限。移动网络 Pages 新版大包复验超时而原地址成功，详见 [V1.9.3](../../docs/V1.9.3.md)。
