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
    @Test fun aiSchemaAndTransportFailuresAreActionableWithoutLeakingRawContent() {
        val schema = CloudErrors.message(502, "/functions/v1/ledger-ai", """{"error":"AI 输出格式修正失败，请重试"}""")
        assertTrue(schema.contains("有效记账格式"))
        val upstream = CloudErrors.message(502, "/functions/v1/ledger-ai", """{"error":"上游服务暂不可用，请稍后重试"}""")
        assertEquals("AI 服务暂时不可用，请稍后重试", upstream)
        val unknown = CloudErrors.message(502, "/functions/v1/ledger-ai", """{"error":"private model output"}""")
        assertFalse(unknown.contains("private"))
        assertTrue(unknown.contains("HTTP 502"))
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
