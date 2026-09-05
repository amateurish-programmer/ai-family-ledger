package com.familyledger.app.data

import com.familyledger.app.domain.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

object SpreadsheetImport {
    fun headers(sheet: String): List<String> = listOf("交易类型", "日期", "一级分类", "二级分类") +
        when (sheet) { "支出" -> listOf("支出账户"); "收入" -> listOf("收入账户"); else -> listOf("账户1", "账户2") } +
        listOf("账户币种", "金额", "成员", "商家", "项目分类", "项目", "记账人", "备注")

    fun preview(bytes: ByteArray, fileName: String, existing: List<LedgerEntry>): ImportPreview {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val ids = existing.map { it.id }.toHashSet()
        val fingerprints = existing.map(::fingerprint).toHashSet()
        val sheets = XlsxCodec.read(bytes)
        val known = setOf("支出", "收入", "余额变更")
        require(sheets.any { it.name in known }) { "未找到支出、收入或余额变更工作表" }
        require(sheets.map { it.name }.distinct().size == sheets.size) { "工作表名称重复" }
        val now = System.currentTimeMillis()
        val rows = mutableListOf<ImportRow>()
        sheets.filter { it.name in known }.forEach { sheet ->
            val header = sheet.rows.firstOrNull() ?: return@forEach
            require(!header.hasFormula) { "${sheet.name}：表头不能包含公式" }
            val mapping = header.cells.mapValues { it.value.trim() }
            require(mapping.values.distinct().size == mapping.size && headers(sheet.name).all { it in mapping.values }) { "${sheet.name}：表头缺失或重复，请使用随手记标准账本" }
            sheet.rows.drop(1).forEach { row ->
                try {
                    require(!row.hasFormula) { "含公式，请先在 Excel 中转换为值" }
                    require(row.cells.keys.all { it in mapping }) { "包含没有表头的数据列" }
                    val raw = mapping.entries.associate { it.value to row.cells[it.key].orEmpty() }
                    fun get(key: String) = raw[key].orEmpty().trim()
                    require(get("交易类型") == sheet.name) { "交易类型与工作表不一致" }
                    val type = when (sheet.name) { "支出" -> EntryType.EXPENSE; "收入" -> EntryType.INCOME; else -> EntryType.BALANCE_ADJUSTMENT }
                    require(get("账户币种") == "CNY") { "仅支持人民币 CNY" }
                    var date = get("日期")
                    if (Regex("[0-9]+(\\.[0-9]+)?").matches(date)) date = XlsxCodec.excelDate(date, sheet.date1904)
                    val day = if (date.length == 10) validateDate(date) else {
                        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}").matches(date)) { "日期须为 YYYY-MM-DD 或带时分秒" }
                        LocalDateTime.parse(date.replace('T', ' '), DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
                        validateDate(date.take(10))
                    }
                    val amountText = get("金额")
                    require(Regex("-?[0-9]+(\\.[0-9]+)?([Ee][+-]?[0-9]+)?").matches(amountText) && amountText.length <= 40) { "金额格式无效" }
                    val amount = BigDecimal(amountText).movePointRight(2).longValueExact()
                    val e = validateEntry(LedgerEntry(
                        id = UUID.nameUUIDFromBytes("$hash|${sheet.name}|${row.number}".toByteArray(Charsets.UTF_8)).toString(),
                        type = type, occurredOn = day, amountMinor = amount,
                        categoryL1 = get("一级分类").ifEmpty { if (type == EntryType.BALANCE_ADJUSTMENT) "余额变更" else "" },
                        categoryL2 = get("二级分类"), account = get(headers(sheet.name)[4]),
                        member = get("成员").ifEmpty { "未指定" }, recordedBy = get("记账人").ifEmpty { "未指定" },
                        merchant = get("商家"), project = get("项目"), note = get("备注"), updatedAt = now,
                        origin = ImportOrigin(hash, fileName.take(255), sheet.name, row.number, now, date, get("账户2"), get("项目分类"), raw)
                    ))
                    val signature = fingerprint(e)
                    val status = when { e.id in ids -> ImportStatus.EXISTING; signature in fingerprints -> ImportStatus.SUSPECTED; else -> ImportStatus.NEW }
                    fingerprints += signature
                    rows += ImportRow(sheet.name, row.number, e, status)
                } catch (_: ArithmeticException) { rows += ImportRow(sheet.name, row.number, null, ImportStatus.ERROR, "金额超出范围或超过两位小数")
                } catch (_: java.time.DateTimeException) { rows += ImportRow(sheet.name, row.number, null, ImportStatus.ERROR, "日期或时间不存在")
                } catch (e: IllegalArgumentException) { rows += ImportRow(sheet.name, row.number, null, ImportStatus.ERROR, e.message ?: "字段无效") }
            }
        }
        require(rows.size <= 10000) { "最多支持 10,000 条记录" }
        return ImportPreview(fileName.take(255), rows, sheets.filter { it.name !in known }.map { it.name })
    }

    fun fingerprint(e: LedgerEntry): String = listOf(e.type.name, e.occurredOn, e.amountMinor.toString(), e.currency,
        e.categoryL1, e.categoryL2, e.account, e.member, e.recordedBy, e.merchant, e.project, e.note,
        e.origin?.account2.orEmpty(), e.origin?.projectCategory.orEmpty()).joinToString("") { "${it.length}:$it" }
}
