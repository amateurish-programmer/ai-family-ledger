package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val entry = LedgerEntry(
        "8e8e2971-29a6-4f29-9e45-59309ba59811", EntryType.EXPENSE,
        "2026-09-05", 6380, "食品酒水", "买菜", "银行卡", "家人", "本人",
        note = "换行\n与引号\"", updatedAt = 1234
    )

    @Test fun roundTripKeepsMoneyOwnershipAndNotes() {
        assertEquals(listOf(entry), BackupCodec.decode(BackupCodec.encode(listOf(entry))))
    }

    @Test fun duplicateIdsInOneFileAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(BackupCodec.encode(listOf(entry, entry)))
        }
    }

    @Test fun futureSchemaAndWrongCurrencyAreRejected() {
        val json = BackupCodec.encode(listOf(entry))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.replace("\"version\":2", "\"version\":99")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.replace("CNY", "USD")) }
    }

    @Test fun fractionalMinorUnitsAndInvalidDateAreRejected() {
        val json = BackupCodec.encode(listOf(entry))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.replace("6380", "6380.5")) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(json.replace("2026-09-05", "2026-02-30")) }
    }

    @Test fun missingDeletionMarkerCannotResurrectRecord() {
        val root = org.json.JSONObject(BackupCodec.encode(listOf(entry.copy(deletedAt = 2000))))
        root.getJSONArray("entries").getJSONObject(0).remove("deletedAt")
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(root.toString()) }
    }
}
