package com.familyledger.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class CloudTransportTest {
    @Test fun currentNetworkCapabilitiesSelectTheFixedRequestDestination() {
        val cases = listOf(
            Triple(true, false, false) to "https://xdgeybztysuvvwagqkvb.supabase.co/rest/v1/rpc/get_my_family",
            Triple(false, true, false) to "https://family-ledger-gateway.pages.dev/rest/v1/rpc/get_my_family",
            Triple(true, false, true) to "https://family-ledger-gateway.pages.dev/rest/v1/rpc/get_my_family",
            Triple(false, false, false) to "https://family-ledger-gateway.pages.dev/rest/v1/rpc/get_my_family",
            Triple(true, true, false) to "https://family-ledger-gateway.pages.dev/rest/v1/rpc/get_my_family"
        )
        for ((network, expected) in cases) {
            val direct = CloudEndpoint.prefersDirect(network.first, network.second, network.third)
            assertEquals(expected, CloudEndpoint.requestUrl(CloudEndpoint.URL, "/rest/v1/rpc/get_my_family", direct))
        }
    }

    @Test fun cellularKeepsTheWorkingOriginalProjectAndReleasePaths() {
        assertEquals("https://xdgeybztysuvvwagqkvb.supabase.co/auth/v1/token?grant_type=password",
            CloudEndpoint.requestUrl(CloudEndpoint.URL, "/auth/v1/token?grant_type=password", preferDirect = true))
        val update = AppUpdate(1, 25, "1.9.3", 26, "com.familyledger.app",
            "releases/25/ai-family-ledger-v1.9.3.apk", 123, "a".repeat(64), "修复网络", Instant.EPOCH)
        assertEquals("https://xdgeybztysuvvwagqkvb.supabase.co/storage/v1/object/public/app-updates/releases/25/ai-family-ledger-v1.9.3.apk",
            update.downloadUrl(preferDirect = true))
        assertEquals("https://xdgeybztysuvvwagqkvb.supabase.co/storage/v1/object/public/app-updates/latest.json",
            AppUpdate.manifestUrl(preferDirect = true))
        assertEquals("https://family-ledger-gateway.pages.dev/updates/latest.json", AppUpdate.manifestUrl(preferDirect = false))
    }

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
