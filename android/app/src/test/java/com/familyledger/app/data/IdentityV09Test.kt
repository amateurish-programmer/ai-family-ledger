package com.familyledger.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IdentityV09Test {
    private data class Call(val method: String, val path: String, val body: JSONObject?, val bearer: String?)
    private val session = """{"access_token":"temporary-access","refresh_token":"temporary-refresh","token_type":"bearer","expires_in":3600,"user":{"id":"00000000-0000-4000-8000-000000000001","email":"test@example.com"}}"""

    @Test fun recoveryUsesOnlyEmailAndDoesNotAuthenticateTheDevice() {
        val calls = mutableListOf<Call>()
        val recovery = PasswordRecovery { method, path, body, bearer -> calls += Call(method, path, body, bearer); "{}" }
        recovery.requestCode(" test@example.com ")
        assertEquals(1, calls.size)
        assertEquals("/auth/v1/recover", calls.single().path)
        assertEquals("test@example.com", calls.single().body!!.getString("email"))
        assertNull(calls.single().bearer)
    }

    @Test fun successfulResetUsesRecoveryTokenAndRevokesOnlyThatSession() {
        val calls = mutableListOf<Call>()
        val recovery = PasswordRecovery { method, path, body, bearer ->
            calls += Call(method, path, body, bearer)
            if (path == "/auth/v1/verify") session else "{}"
        }
        assertTrue(recovery.reset("test@example.com", "123456", "new-password").contains("成功"))
        assertEquals(listOf("POST", "PUT", "POST"), calls.map { it.method })
        assertEquals(listOf("/auth/v1/verify", "/auth/v1/user", "/auth/v1/logout?scope=local"), calls.map { it.path })
        assertEquals("recovery", calls[0].body!!.getString("type"))
        assertEquals("123456", calls[0].body!!.getString("token"))
        assertEquals("test@example.com", calls[0].body!!.getString("email"))
        assertFalse(calls[0].body!!.has("password"))
        assertNull(calls[0].bearer)
        assertEquals("temporary-access", calls[1].bearer)
        assertEquals("new-password", calls[1].body!!.getString("password"))
        assertEquals("temporary-access", calls[2].bearer)
    }

    @Test fun invalidCodeCannotUpdatePasswordAndCanRetryWithNewCode() {
        val paths = mutableListOf<String>()
        val recovery = PasswordRecovery { _, path, body, _ ->
            paths += path
            if (path == "/auth/v1/verify") {
                if (body!!.getString("token") == "111111") error("验证码无效或已过期")
                session
            } else "{}"
        }
        assertThrows(IllegalStateException::class.java) { recovery.reset("test@example.com", "111111", "new-password") }
        assertEquals(listOf("/auth/v1/verify"), paths)
        recovery.reset("test@example.com", "222222", "new-password")
        assertEquals(1, paths.count { it == "/auth/v1/user" })
    }

    @Test fun updateFailureStillRevokesAndRequiresFreshCode() {
        val paths = mutableListOf<String>()
        val recovery = PasswordRecovery { _, path, _, _ ->
            paths += path
            when (path) { "/auth/v1/verify" -> session; "/auth/v1/user" -> error("network"); else -> "{}" }
        }
        val failure = assertThrows(IllegalStateException::class.java) { recovery.reset("test@example.com", "123456", "new-password") }
        assertTrue(failure.message!!.contains("重新获取验证码"))
        assertEquals("/auth/v1/logout?scope=local", paths.last())
    }

    @Test fun revocationFailureDoesNotTurnConfirmedPasswordChangeIntoFailure() {
        val recovery = PasswordRecovery { _, path, _, _ ->
            when (path) { "/auth/v1/verify" -> session; "/auth/v1/user" -> "{}"; else -> error("offline") }
        }
        assertTrue(recovery.reset("test@example.com", "123456", "new-password").contains("成功"))
    }

    @Test fun mismatchedRecoveryAccountIsNeverUpdated() {
        val paths = mutableListOf<String>()
        val recovery = PasswordRecovery { _, path, _, _ -> paths += path; if (path == "/auth/v1/verify") session else "{}" }
        assertThrows(IllegalStateException::class.java) { recovery.reset("other@example.com", "123456", "new-password") }
        assertFalse(paths.contains("/auth/v1/user"))
        assertEquals("/auth/v1/logout?scope=local", paths.last())
    }

    @Test fun badInputsAreRejectedBeforeAnyNetworkRequest() {
        var requests = 0
        val recovery = PasswordRecovery { _, _, _, _ -> requests++; "{}" }
        assertThrows(IllegalArgumentException::class.java) { recovery.requestCode("not-an-email") }
        assertThrows(IllegalArgumentException::class.java) { recovery.reset("test@example.com", "abc123", "new-password") }
        assertThrows(IllegalArgumentException::class.java) { recovery.reset("test@example.com", "123456", "short") }
        assertEquals(0, requests)
    }

    @Test fun fixedProfileChoicesRejectUntrustedValuesAndKeepDefaultsCompatible() {
        assertEquals("home", IdentityProfile.familyIcon(null))
        assertEquals("person", IdentityProfile.avatar(null))
        assertThrows(IllegalArgumentException::class.java) { IdentityProfile.familyIcon("https://bad.example/icon") }
        assertThrows(IllegalArgumentException::class.java) { IdentityProfile.avatar("owner") }
        assertEquals("家人的家", IdentityProfile.familyName(" 家人的家 "))
        assertThrows(IllegalArgumentException::class.java) { IdentityProfile.familyName(" ") }
        assertThrows(IllegalArgumentException::class.java) { IdentityProfile.familyName("a".repeat(81)) }
    }

    @Test fun recoveryErrorsAreActionableAndNeverEchoSensitiveServerText() {
        assertTrue(CloudErrors.message(403, "/auth/v1/verify", """{"error_code":"otp_expired"}""").contains("验证码"))
        assertTrue(CloudErrors.message(422, "/auth/v1/user", """{"code":"same_password"}""").contains("原密码"))
        assertTrue(CloudErrors.message(500, "/auth/v1/recover", """{"msg":"Error sending recovery email"}""").contains("重置邮件"))
        assertFalse(CloudErrors.message(400, "/auth/v1/verify", """{"msg":"secret-otp private-password"}""").contains("secret"))
        assertTrue(CloudErrors.message(403, "/rest/v1/rpc/update_family_profile", """{"message":"owner_required"}""").contains("家庭资料"))
    }
}
