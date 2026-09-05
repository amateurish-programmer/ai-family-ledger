package com.familyledger.app.domain

import java.time.LocalDate
import java.math.BigDecimal

enum class EntryType(val label: String) { EXPENSE("支出"), INCOME("收入"), BALANCE_ADJUSTMENT("余额调整") }

data class LedgerEntry(
    val id: String,
    val type: EntryType,
    val occurredOn: String,
    val amountMinor: Long,
    val categoryL1: String,
    val categoryL2: String = "",
    val account: String,
    val member: String,
    val recordedBy: String,
    val merchant: String = "",
    val project: String = "",
    val note: String = "",
    val currency: String = "CNY",
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val origin: ImportOrigin? = null
)

data class CategoryTotal(val name: String, val amount: Long)
data class Summary(val income: Long, val expense: Long, val categories: List<CategoryTotal>) {
    val balance: Long get() = Math.subtractExact(income, expense)
}

object Money {
    const val MAX_MINOR = 999_999_999_99L
    fun parse(text: String): Long {
        val value = text.trim()
        require(Regex("[0-9]{1,9}(\\.[0-9]{1,2})?").matches(value)) { "请输入有效金额，最多两位小数" }
        val cents = BigDecimal(value).movePointRight(2).longValueExact()
        require(cents in 1..MAX_MINOR) { "金额须大于零且不超过 999999999.99" }
        return cents
    }
    fun format(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
}
fun validateDate(text: String): String {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)) { "日期格式应为 YYYY-MM-DD" }
    val date = try { LocalDate.parse(text) } catch (_: java.time.DateTimeException) {
        throw IllegalArgumentException("日期不存在，请检查年月日")
    }
    require(date.year in 1..9999) { "年份应在 0001 至 9999 之间" }
    return date.toString()
}

fun validateEntry(entry: LedgerEntry): LedgerEntry {
    validateDate(entry.occurredOn)
    require(entry.currency == "CNY") { "当前仅支持人民币 CNY" }
    require(entry.amountMinor in (if (entry.type == EntryType.BALANCE_ADJUSTMENT) -Money.MAX_MINOR else 1L)..Money.MAX_MINOR) { "金额超出允许范围" }
    listOf(entry.categoryL1, entry.account, entry.member, entry.recordedBy).forEach {
        require(it.isNotBlank()) { "分类、账户、成员和记账人不能为空" }
    }
    listOf(entry.categoryL1, entry.categoryL2, entry.account, entry.member, entry.recordedBy, entry.merchant, entry.project).forEach {
        require(it.length <= 100) { "字段不得超过 100 个字符" }
    }
    require(entry.note.length <= 2000) { "备注不得超过 2000 个字符" }
    require(entry.updatedAt >= 0 && (entry.deletedAt == null || entry.deletedAt >= 0)) { "备份时间无效" }
    entry.origin?.let { origin ->
        require(Regex("[0-9a-f]{64}").matches(origin.fileHash) && origin.rowNumber in 1..10001 && origin.importedAt >= 0) { "导入来源无效" }
        require(origin.fileName.length <= 255 && origin.sheet.length <= 100 && origin.originalDate.length <= 100) { "导入来源过长" }
        require(origin.account2.length <= 100 && origin.projectCategory.length <= 100 && origin.rawFields.size <= 64) { "导入字段过长" }
        require(origin.rawFields.all { it.key.length <= 100 && it.value.length <= 10000 }) { "原始字段过长" }
    }
    return entry.copy(categoryL1 = entry.categoryL1.trim(), categoryL2 = entry.categoryL2.trim(),
        account = entry.account.trim(), member = entry.member.trim(), recordedBy = entry.recordedBy.trim(),
        merchant = entry.merchant.trim(), project = entry.project.trim(), note = entry.note.trim())
}

fun summarize(entries: List<LedgerEntry>, start: LocalDate, endExclusive: LocalDate): Summary {
    require(start < endExclusive)
    val filtered = entries.filter { it.deletedAt == null && it.currency == "CNY" && it.occurredOn >= start.toString() && it.occurredOn < endExclusive.toString() }
    fun total(rows: List<LedgerEntry>) = rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
    val expenses = filtered.filter { it.type == EntryType.EXPENSE }
    return Summary(total(filtered.filter { it.type == EntryType.INCOME }), total(expenses),
        expenses.groupBy { it.categoryL1 }.map { CategoryTotal(it.key, total(it.value)) }.sortedByDescending { it.amount })
}
