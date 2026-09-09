package com.familyledger.app.data

import com.familyledger.app.domain.EntryType
import com.familyledger.app.domain.LedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncResultMessageTest {
    @Test fun zeroChangesStaySilent() {
        assertNull(SyncResult(0, 0, emptyList()).compactMessage())
    }

    @Test fun onlyNonzeroCountsAreShownInStableOrder() {
        val row = LedgerEntry("id", EntryType.EXPENSE, "2026-09-09", 100, "测试",
            account = "现金", member = "家人", recordedBy = "本人")
        val conflict = CloudConflict("id", row, row, 2)
        assertEquals("上传 2 条", SyncResult(2, 0, emptyList()).compactMessage())
        assertEquals("下载 3 条 / 冲突 1 条", SyncResult(0, 3, listOf(conflict)).compactMessage())
        assertEquals("上传 2 条 / 下载 3 条 / 冲突 1 条", SyncResult(2, 3, listOf(conflict)).compactMessage())
    }
}
