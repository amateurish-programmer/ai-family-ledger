package com.familyledger.app.data

import org.junit.Assert.*
import org.junit.Test

class CloudErrorsTest {
    @Test fun confirmationMailFailureIsActionable() {
        assertTrue(CloudErrors.message(500, "/auth/v1/signup", """{"code":500,"error_code":"unexpected_failure","msg":"Error sending confirmation email"}""").contains("验证邮件发送失败"))
    }
    @Test fun authCodeAndLegacyCodeAreRecognized() {
        assertEquals("邮箱或密码不正确", CloudErrors.message(400, "/auth/v1/token", """{"error_code":"invalid_credentials"}"""))
        assertEquals("请先完成邮箱验证", CloudErrors.message(400, "/auth/v1/token", """{"code":"email_not_confirmed"}"""))
    }
    @Test fun authRateLimitDoesNotMentionAi() {
        assertFalse(CloudErrors.message(429, "/auth/v1/signup", "{}").contains("AI"))
    }
    @Test fun rawServerContentIsNeverDisplayed() {
        val message = CloudErrors.message(500, "/auth/v1/signup", """{"message":"private@example.com private-token"}""")
        assertFalse(message.contains("private"))
        assertTrue(message.contains("500"))
    }
    @Test fun rpcErrorsStillUseKnownMessage() {
        assertTrue(CloudErrors.message(400, "/rest/v1/rpc/join_family", """{"code":"P0001","message":"invalid_or_expired_invite"}""").contains("邀请码"))
    }
    @Test fun freshInstallAndSameProjectCanUseBuiltInConfiguration() {
        CloudEndpoint.requireCompatible(null, false)
        CloudEndpoint.requireCompatible(CloudEndpoint.URL, true)
        CloudEndpoint.requireCompatible("https://unused.supabase.co", false)
    }
    @Test fun existingForeignProjectIdentityCannotBeMoved() {
        assertThrows(IllegalArgumentException::class.java) { CloudEndpoint.requireCompatible("https://other.supabase.co", true) }
        assertThrows(IllegalArgumentException::class.java) { CloudEndpoint.requireCompatible(null, true) }
    }
}
