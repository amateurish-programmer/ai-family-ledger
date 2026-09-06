package com.familyledger.app.data

import com.familyledger.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SpreadsheetImportTest {
    @Test fun reorderedHeadersAndSharedStringsPreserveOrigin() {
        val order = listOf(6, 1, 0, 4, 5, 3, 2, 7, 8, 9, 10, 11, 12)
        val bytes = SampleWorkbooks.book(listOf(order.map { SampleWorkbooks.headers[it] }, order.map { SampleWorkbooks.expense[it] }), shared = true)
        val row = SpreadsheetImport.preview(bytes, "合成.xlsx", emptyList()).rows.single()
        assertEquals(ImportStatus.NEW, row.status)
        val e = row.entry!!
        assertEquals(3680L, e.amountMinor)
        assertEquals("食品酒水", e.categoryL1)
        assertEquals("2026-09-06", e.occurredOn)
        assertEquals("2026-09-06 12:30:01", e.origin!!.originalDate)
        assertEquals("生活", e.origin!!.projectCategory)
        assertEquals("食品酒水 ", e.origin!!.rawFields["一级分类"])
        assertEquals("合成记录\n第二行", e.note)
        assertEquals("家人", e.member)
        assertEquals("本人", e.recordedBy)
    }
    @Test fun sameFileEvenRenamedOrDeletedCannotBeImportedAgain() {
        val bytes = SampleWorkbooks.book()
        val e = SpreadsheetImport.preview(bytes, "a.xlsx", emptyList()).rows.single().entry!!
        val row = SpreadsheetImport.preview(bytes, "renamed.xlsx", listOf(e.copy(deletedAt = 50))).rows.single()
        assertEquals(e.id, row.entry!!.id)
        assertEquals(ImportStatus.EXISTING, row.status)
    }
    @Test fun similarRowsAcrossFilesAndWithinFileAreOnlySuspected() {
        val original = SpreadsheetImport.preview(SampleWorkbooks.book(), "a.xlsx", emptyList()).rows.single().entry!!
        val b = SpreadsheetImport.preview(SampleWorkbooks.book(suffix = "different file"), "b.xlsx", listOf(original))
        assertEquals(ImportStatus.SUSPECTED, b.rows.single().status)
        val same = SpreadsheetImport.preview(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, SampleWorkbooks.expense, SampleWorkbooks.expense)), "c.xlsx", emptyList())
        assertEquals(listOf(ImportStatus.NEW, ImportStatus.SUSPECTED), same.rows.map { it.status })
        assertNotEquals(same.rows[0].entry!!.id, same.rows[1].entry!!.id)
    }
    @Test fun oldExportPlusSameDaySameAmountAtDifferentTimeSelectsOnlyNewTransaction() {
        val old = SpreadsheetImport.preview(SampleWorkbooks.book(), "old.xlsx", emptyList()).rows.single().entry!!
        val later = SampleWorkbooks.expense.toMutableList().apply { this[1] = "2026-09-06 18:30:01" }
        val preview = SpreadsheetImport.preview(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, SampleWorkbooks.expense, later)), "new.xlsx", listOf(old))
        assertEquals(listOf(ImportStatus.SUSPECTED, ImportStatus.NEW), preview.rows.map { it.status })
        assertEquals(3680L, preview.rows.single { it.status == ImportStatus.NEW }.entry!!.amountMinor)
    }
    @Test fun reorderingExportDoesNotTurnOldTransactionsIntoNewOnes() {
        val later = SampleWorkbooks.expense.toMutableList().apply { this[1] = "2026-09-06 18:30:01" }
        val original = SpreadsheetImport.preview(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, SampleWorkbooks.expense, later)), "old.xlsx", emptyList())
        assertEquals(listOf(ImportStatus.NEW, ImportStatus.NEW), original.rows.map { it.status })
        val reordered = SpreadsheetImport.preview(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, later, SampleWorkbooks.expense)), "sorted.xlsx", original.rows.mapNotNull { it.entry })
        assertEquals(listOf(ImportStatus.SUSPECTED, ImportStatus.SUSPECTED), reordered.rows.map { it.status })
    }
    @Test fun sameTimestampWithDifferentSeparatorIsStillSuspected() {
        val old = SpreadsheetImport.preview(SampleWorkbooks.book(), "old.xlsx", emptyList()).rows.single().entry!!
        val equivalent = SampleWorkbooks.expense.toMutableList().apply { this[1] = "2026-09-06T12:30:01" }
        assertEquals(ImportStatus.SUSPECTED, SpreadsheetImport.preview(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, equivalent)), "new.xlsx", listOf(old)).rows.single().status)
    }
    @Test fun missingTimeOnEitherSideKeepsConservativeDuplicatePrompt() {
        val knownTime = SpreadsheetImport.preview(SampleWorkbooks.book(), "known.xlsx", emptyList()).rows.single().entry!!
        val dateOnly = SampleWorkbooks.expense.toMutableList().apply { this[1] = "2026-09-06" }
        val bytes = SampleWorkbooks.book(listOf(SampleWorkbooks.headers, dateOnly))
        val withoutTime = SpreadsheetImport.preview(bytes, "day.xlsx", emptyList()).rows.single().entry!!
        assertEquals(ImportStatus.SUSPECTED, SpreadsheetImport.preview(bytes, "day.xlsx", listOf(knownTime)).rows.single().status)
        assertEquals(ImportStatus.SUSPECTED, SpreadsheetImport.preview(SampleWorkbooks.book(), "known.xlsx", listOf(withoutTime)).rows.single().status)
    }
    @Test fun oldSourceRemainsSuspectedAfterLocalEditAndDeletion() {
        val original = SpreadsheetImport.preview(SampleWorkbooks.book(), "old.xlsx", emptyList()).rows.single().entry!!
        val edited = original.copy(amountMinor = 5000, note = "本地修正", occurredOn = "2026-09-07", deletedAt = 50)
        val preview = SpreadsheetImport.preview(SampleWorkbooks.book(suffix = "new export"), "new.xlsx", listOf(edited))
        assertEquals(ImportStatus.SUSPECTED, preview.rows.single().status)
        assertEquals(5000L, edited.amountMinor)
        assertEquals(50L, edited.deletedAt!!)
    }
    @Test fun sourceChangesAreNewCandidatesAndNeverOverwriteExistingIds() {
        val original = SpreadsheetImport.preview(SampleWorkbooks.book(), "old.xlsx", emptyList()).rows.single().entry!!
        val changed = SampleWorkbooks.expense.toMutableList().apply { this[6] = "40.00" }
        val preview = SpreadsheetImport.preview(SampleWorkbooks.book(listOf(SampleWorkbooks.headers, changed)), "edited.xlsx", listOf(original))
        assertEquals(ImportStatus.NEW, preview.rows.single().status)
        assertNotEquals(original.id, preview.rows.single().entry!!.id)
        assertEquals(3680L, original.amountMinor)
        assertEquals(4000L, preview.rows.single().entry!!.amountMinor)
        val removed = SpreadsheetImport.preview(SampleWorkbooks.book(listOf(SampleWorkbooks.headers)), "removed.xlsx", listOf(original))
        assertTrue(removed.rows.isEmpty())
        assertNull(original.deletedAt)
    }
    @Test fun invalidDateCurrencyAndPrecisionAreVisibleErrors() {
        val rows = listOf(SampleWorkbooks.headers) + listOf(1 to "2026-02-30", 5 to "USD", 6 to "36.801").map { (index, value) ->
            SampleWorkbooks.expense.toMutableList().apply { this[index] = value }
        }
        val preview = SpreadsheetImport.preview(SampleWorkbooks.book(rows), "bad.xlsx", emptyList())
        assertEquals(3, preview.rows.size)
        assertTrue(preview.rows.all { it.status == ImportStatus.ERROR && it.error.isNotBlank() && it.entry == null })
    }
    @Test fun balanceChangesPreserveSignedAmountButNeverAffectReport() {
        val header = SampleWorkbooks.headers.toMutableList().apply { this[4] = "账户1"; add(5, "账户2") }
        val values = SampleWorkbooks.expense.toMutableList().apply { this[0] = "余额变更"; this[6] = "-2.50"; add(5, "现金") }
        val e = SpreadsheetImport.preview(SampleWorkbooks.book(listOf(header, values), "余额变更"), "balance.xlsx", emptyList()).rows.single().entry!!
        assertEquals(-250L, e.amountMinor)
        assertEquals("现金", e.origin!!.account2)
        assertEquals(0L, summarize(listOf(e), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)).balance)
    }
    @Test fun spreadsheetExportRoundTripPreservesFieldsAndNeverCreatesFormula() {
        val e = SpreadsheetImport.preview(SampleWorkbooks.book(), "a.xlsx", emptyList()).rows.single().entry!!.copy(note = "=1+1 & <text>")
        val bytes = XlsxCodec.write(listOf(e))
        val r = SpreadsheetImport.preview(bytes, "export.xlsx", emptyList()).rows.single().entry!!
        assertEquals(e.amountMinor, r.amountMinor)
        assertEquals(e.note, r.note)
        assertEquals(e.origin!!.originalDate, r.origin!!.originalDate)
        assertEquals(e.origin!!.projectCategory, r.origin!!.projectCategory)
    }
    @Test fun missingHeaderIsRejectedBeforeAnyPreview() {
        assertThrows(IllegalArgumentException::class.java) {
            SpreadsheetImport.preview(SampleWorkbooks.book(listOf(listOf("金额"), listOf("12"))), "bad.xlsx", emptyList())
        }
    }
    @Test fun dtdAndNonZipInputAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { XlsxCodec.read("not a workbook".toByteArray()) }
        val xml = "<!DOCTYPE workbook [<!ENTITY x 'expanded'>]><workbook>&x;</workbook>"
        assertThrows(IllegalArgumentException::class.java) { XlsxCodec.read(SampleWorkbooks.zip(mapOf("xl/workbook.xml" to xml))) }
    }
}
