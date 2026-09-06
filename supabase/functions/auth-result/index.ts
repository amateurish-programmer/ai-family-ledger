// Supabase's shared domain serves HTML as plain text, so use readable text.
// Auth has already handled the one-time link before redirecting here. URL
// fragments are not sent to this endpoint; never claim verification succeeded.
Deno.serve((request: Request) => {
  const headers = {
    "Content-Type": "text/plain; charset=utf-8",
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
  };
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", { status: 405, headers: { ...headers, Allow: "GET, HEAD" } });
  }
  return new Response(request.method === "HEAD" ? null : `AI 家庭账本 · 邮箱验证

请返回 Android App，使用注册邮箱和密码登录。

如果可以登录，说明邮箱验证已经完成。
如果仍提示“邮箱未验证”，请重新请求注册邮件，并打开最新邮件中的链接。
验证链接只能使用一次；旧链接、过期链接或再次点击已使用的链接可能无法验证。

此页仅提供操作指引，不代表邮箱已经验证成功。请以 App 登录结果为准。
无需在电脑上运行 localhost，也无需重新安装 App。
`, { headers });
});
