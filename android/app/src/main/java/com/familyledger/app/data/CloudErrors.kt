package com.familyledger.app.data

import org.json.JSONObject

/** Translate allowlisted server errors without exposing raw account data or upstream details. */
object CloudErrors {
    fun message(status: Int, path: String, response: String): String {
        val error = runCatching { JSONObject(response) }.getOrNull()
        val codes = listOf("error_code", "code", "error", "message").map { error?.optString(it).orEmpty() }
        val auth = path.startsWith("/auth/")
        val mailFailure = path == "/auth/v1/signup" && listOf("msg", "message", "error_description")
            .any { error?.optString(it)?.contains("Error sending confirmation email", ignoreCase = true) == true }
        if (mailFailure) return "验证邮件发送失败，请联系管理员检查发信服务，稍后重试（HTTP $status）"
        for (code in codes) {
            when (code) {
                "invalid_credentials" -> return "邮箱或密码不正确"
                "email_not_confirmed" -> return "请先完成邮箱验证"
                "email_address_invalid", "validation_failed" -> return "邮箱或注册信息无效，请检查后重试"
                "weak_password" -> return "密码强度不足，请使用更长且包含字母和数字的密码"
                "email_exists", "user_already_exists" -> return "邮箱已注册，请直接登录"
                "email_address_not_authorized" -> return "注册邮件暂时无法发送到此邮箱，请联系管理员检查发信服务"
                "over_email_send_rate_limit" -> return "验证邮件发送过于频繁，请稍后重试并检查垃圾邮件"
                "over_request_rate_limit" -> return "请求过于频繁，请稍后重试"
                "signup_disabled", "email_provider_disabled" -> return "注册服务暂未开放，请联系管理员"
                "invalid_invite", "invalid_or_expired_invite" -> return "邀请码不存在、已使用或已过期"
                "invite_limit" -> return "有效待用邀请码已达十个，请先使用或等待到期"
                "owner_required" -> return "只有家庭创建者可以生成邀请码"
            }
        }
        return when {
            auth && status >= 500 -> "账号服务暂时异常，请联系管理员检查注册和邮件服务（HTTP $status）"
            auth && status == 429 -> "账号请求过于频繁，请稍后重试"
            auth && status in listOf(400, 422) -> "账号请求未成功，请检查邮箱、密码和验证状态（HTTP $status）"
            status == 401 -> "登录已失效，请重新登录"
            status == 403 -> "账号无家庭权限或操作仅限家庭创建者"
            status == 429 && path.startsWith("/functions/") -> "请求过于频繁或 AI 今日额度已用完"
            status == 429 -> "请求过于频繁，请稍后重试"
            else -> "云端服务暂时异常，请稍后重试或联系管理员（HTTP $status）"
        }
    }
}
