package com.familyledger.app.data

import org.json.JSONObject

/** Isolated recovery transport: no device session store and no refresh/accept-session path. */
internal class PasswordRecovery(private val request: (String, String, JSONObject?, String?) -> String) {
    private fun normalizedEmail(value: String): String = value.trim().also {
        require(it.length in 3..254 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(it)) { "请输入有效邮箱" }
    }

    fun requestCode(email: String): String {
        request("POST", "/auth/v1/recover", JSONObject().put("email", normalizedEmail(email)), null)
        return "如邮箱已注册，将收到重置验证码；请检查收件箱和垃圾邮件，稍后可重新发送"
    }

    fun reset(email: String, code: String, newPassword: String): String {
        val address = normalizedEmail(email)
        val otp = code.trim()
        require(Regex("[0-9]{6,10}").matches(otp)) { "请输入邮件中的完整数字验证码" }
        require(newPassword.length in 8..256) { "新密码须为 8 至 256 个字符" }
        val response = JSONObject(request("POST", "/auth/v1/verify",
            JSONObject().put("email", address).put("token", otp).put("type", "recovery"), null))
        val access = response.optString("access_token")
        try {
            check(access.isNotBlank()) { "恢复验证响应无效，请重新获取验证码" }
            check(response.optJSONObject("user")?.optString("email")?.equals(address, ignoreCase = true) == true) {
                "恢复验证账号不匹配，请重新获取验证码"
            }
            try {
                request("PUT", "/auth/v1/user", JSONObject().put("password", newPassword), access)
            } catch (failure: Exception) {
                // A dropped response may hide a successful update; the consumed OTP cannot be reused.
                val advice = when {
                    failure.message?.contains("密码强度") == true -> "密码强度不足，请设置更长且包含字母和数字的密码"
                    failure.message?.contains("原密码") == true -> "新密码不能与原密码相同"
                    else -> "密码更新结果未确认，请先尝试使用新密码登录"
                }
                throw IllegalStateException("$advice；若需再重置，请重新获取验证码")
            }
            return "密码已重置成功，请使用新密码登录"
        } finally {
            // Only this temporary session is revoked. Never persist or accept its access/refresh token.
            if (access.isNotBlank()) runCatching { request("POST", "/auth/v1/logout?scope=local", null, access) }
        }
    }
}
