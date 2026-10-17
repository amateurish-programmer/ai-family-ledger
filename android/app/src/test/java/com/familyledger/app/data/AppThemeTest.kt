package com.familyledger.app.data

import org.junit.Assert.*
import org.junit.Test

class AppThemeTest {
    @Test fun missingOrUnsupportedPreferencesUseWarmDefault() {
        listOf(null, "", "dark", "WARM", "journal ").forEach {
            assertEquals(AppTheme.WARM, AppTheme.fromStored(it))
        }
    }

    @Test fun persistedIdentifiersRestoreEachThemeWithoutDependingOnLabels() {
        assertEquals(listOf("warm", "journal", "finance"), AppTheme.entries.map { it.storageId })
        AppTheme.entries.forEach { assertEquals(it, AppTheme.fromStored(it.storageId)) }
        assertEquals(3, AppTheme.entries.map { it.label }.distinct().size)
    }
}
