package com.familyledger.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class CloudTransportTest {
    @Test fun existingProjectBindingUsesGatewayWithoutChangingItsIdentity() {
        CloudEndpoint.requireCompatible("https://xdgeybztysuvvwagqkvb.supabase.co", true)
        assertEquals("https://family-ledger-gateway.pages.dev/auth/v1/token?grant_type=refresh_token",
            CloudEndpoint.requestUrl("https://xdgeybztysuvvwagqkvb.supabase.co", "/auth/v1/token?grant_type=refresh_token"))
        assertThrows(IllegalArgumentException::class.java) {
            CloudEndpoint.requestUrl("https://other.supabase.co", "/rest/v1/rpc/get_my_family")
        }
        for (path in listOf("//evil.invalid/path", "https://evil.invalid", "/\\evil", "/path#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { CloudEndpoint.requestUrl(CloudEndpoint.URL, path) }
        }
    }

    @Test fun updateDownloadUsesWifiReachableGatewayWithTheOriginalReleasePath() {
        val update = AppUpdate(1, 24, "1.9.2", 26, "com.familyledger.app",
            "releases/24/ai-family-ledger-v1.9.2.apk", 123, "a".repeat(64), "修复网络", Instant.EPOCH)
        assertEquals("https://family-ledger-gateway.pages.dev/updates/releases/24/ai-family-ledger-v1.9.2.apk", update.downloadUrl())
    }
}
