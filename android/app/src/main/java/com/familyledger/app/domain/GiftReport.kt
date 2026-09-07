package com.familyledger.app.domain

/** Confirmed metadata only; names are trimmed, never inferred or merged. */
data class GiftReport(val income: Long, val expense: Long,
    val incomeRanking: List<CategoryTotal>, val expenseRanking: List<CategoryTotal>, val entries: List<LedgerEntry>) {
    val difference: Long get() = Math.subtractExact(income, expense)
    val pendingEntries: List<LedgerEntry> get() = entries.filter { it.counterparty.trim().isEmpty() }
    fun forCounterparty(name: String): List<LedgerEntry> = entries.filter { it.counterparty.trim() == name.trim() }
}

/** A null range explicitly means all history; otherwise [start, endExclusive). */
fun buildGiftReport(entries: List<LedgerEntry>, range: ReportRange? = null): GiftReport {
    if (range != null) require(range.start < range.endExclusive)
    val rows = entries.filter {
        it.isGift && it.deletedAt == null && it.currency == "CNY" &&
            (it.type == EntryType.INCOME || it.type == EntryType.EXPENSE) &&
            (range == null || (it.occurredOn >= range.start.toString() && it.occurredOn < range.endExclusive.toString()))
    }.sortedWith(compareByDescending<LedgerEntry> { it.occurredOn }.thenBy { it.id })
    fun total(items: List<LedgerEntry>) = items.fold(0L) { sum, entry -> Math.addExact(sum, entry.amountMinor) }
    fun ranking(type: EntryType) = rows.filter { it.type == type && it.counterparty.trim().isNotEmpty() }
        .groupBy { it.counterparty.trim() }.map { (name, items) -> CategoryTotal(name, total(items)) }
        .sortedWith(compareByDescending<CategoryTotal> { it.amount }.thenBy { it.name })
    return GiftReport(total(rows.filter { it.type == EntryType.INCOME }),
        total(rows.filter { it.type == EntryType.EXPENSE }), ranking(EntryType.INCOME), ranking(EntryType.EXPENSE), rows)
}
