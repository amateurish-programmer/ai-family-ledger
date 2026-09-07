package com.familyledger.app.data

import com.familyledger.app.domain.*
import java.io.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.zip.*
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.*
import org.xml.sax.ext.DefaultHandler2

data class SheetRow(val number: Int, val cells: Map<Int, String>, val hasFormula: Boolean = false)
data class SheetRows(val name: String, val rows: List<SheetRow>, val date1904: Boolean = false)

/** Limited SpreadsheetML data exchange, with bounded expansion and no formula evaluation. */
object XlsxCodec {
    const val MAX_BYTES = 5 * 1024 * 1024
    private const val MAX_EXPANDED = 16 * 1024 * 1024
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    fun read(bytes: ByteArray): List<SheetRows> {
        require(bytes.size <= MAX_BYTES) { "Excel 文件超过 5 MB" }
        try {
            val parts = linkedMapOf<String, ByteArray>()
            var expanded = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(parts.size < 256 && !parts.containsKey(entry.name)) { "工作簿文件结构无效" }
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer); if (n == -1) break
                        expanded += n; require(expanded <= MAX_EXPANDED) { "Excel 解压内容超过 16 MB" }
                        out.write(buffer, 0, n)
                    }
                    parts[entry.name] = out.toByteArray()
                }
            }
            val workbook = parts["xl/workbook.xml"] ?: throw IllegalArgumentException("请选择未加密的 .xlsx 工作簿")
            val sheets = mutableListOf<Pair<String, String>>()
            var date1904 = false
            parse(workbook, object : SafeHandler() {
                override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                    if (local == "workbookPr") date1904 = a.getValue("date1904") in listOf("1", "true")
                    if (local == "sheet") sheets += a.getValue("name") to (a.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id") ?: a.getValue("r:id"))
                }
            })
            val relations = mutableMapOf<String, String>()
            parse(parts["xl/_rels/workbook.xml.rels"] ?: error("缺少工作簿关系"), object : SafeHandler() {
                override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                    if (local == "Relationship" && a.getValue("Type").endsWith("/worksheet")) {
                        require(a.getValue("TargetMode") != "External") { "不支持外部工作表" }
                        val target = a.getValue("Target")
                        val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
                        require(!path.contains("..") && !path.contains('\\')) { "工作表路径无效" }
                        relations[a.getValue("Id")] = path
                    }
                }
            })
            val shared = mutableListOf<String>()
            parts["xl/sharedStrings.xml"]?.let { xml -> parse(xml, object : SafeHandler() {
                val value = StringBuilder(); var inText = false; var phonetic = false
                override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                    if (local == "si") value.setLength(0)
                    if (local == "rPh") phonetic = true
                    if (local == "t" && !phonetic) inText = true
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inText) { value.append(ch, start, length); require(value.length <= 10000) } }
                override fun endElement(uri: String?, local: String, q: String?) {
                    if (local == "t") inText = false
                    if (local == "rPh") phonetic = false
                    if (local == "si") { require(shared.size < 100000); shared += value.toString() }
                }
            }) }
            var rowCount = 0
            return sheets.map { (name, relation) ->
                val rows = mutableListOf<SheetRow>()
                val seenNumbers = hashSetOf<Int>()
                val path = relations[relation] ?: error("工作表关系缺失")
                parse(parts[path] ?: error("工作表缺失"), object : SafeHandler() {
                    var number = 0; var column = 0; var type = ""; var capture = false; var formula = false
                    var cells = linkedMapOf<Int, String>(); val value = StringBuilder()
                    override fun startElement(uri: String?, local: String, q: String?, a: Attributes) {
                        when (local) {
                            "row" -> { number = a.getValue("r")?.toInt() ?: number + 1; require(number in 1..10001); cells = linkedMapOf(); formula = false }
                            "c" -> {
                                val ref = a.getValue("r") ?: error("单元格地址缺失")
                                column = ref.takeWhile { it in 'A'..'Z' }.fold(0) { n, c -> n * 26 + (c - 'A') + 1 } - 1
                                require(column in 0..63 && !cells.containsKey(column)) { "列数超过 64 或地址重复" }
                                type = a.getValue("t") ?: "n"; value.setLength(0)
                            }
                            "f" -> formula = true
                            "v", "t" -> capture = true
                        }
                    }
                    override fun characters(ch: CharArray, start: Int, length: Int) { if (capture) { value.append(ch, start, length); require(value.length <= 10000) { "单元格文本过长" } } }
                    override fun endElement(uri: String?, local: String, q: String?) {
                        when (local) {
                            "v", "t" -> capture = false
                            "c" -> cells[column] = if (type == "s") shared.getOrNull(value.toString().toInt()) ?: error("共享字符串索引无效") else value.toString()
                            "row" -> if (cells.values.any { it.isNotBlank() } || formula) {
                                rowCount++; require(rowCount <= 10003) { "最多支持 10,000 条记录" }
                                require(seenNumbers.add(number)) { "行号重复" }
                                rows += SheetRow(number, cells, formula)
                            }
                        }
                    }
                })
                SheetRows(name, rows, date1904)
            }
        } catch (e: IllegalArgumentException) { throw e
        } catch (_: Exception) { throw IllegalArgumentException("Excel 文件损坏、加密或格式不受支持") }
    }

    private open class SafeHandler : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTD disabled") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entity disabled") }
    }
    private fun parse(bytes: ByteArray, handler: SafeHandler) {
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    }

    fun excelDate(value: String, date1904: Boolean): String {
        val serial = BigDecimal(value)
        val days = serial.setScale(0, RoundingMode.FLOOR).longValueExact()
        require(days >= 0 && (date1904 || days != 60L)) { "Excel 日期无效" }
        val base = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 31)
        val seconds = serial.subtract(BigDecimal.valueOf(days)).multiply(BigDecimal(86400)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val date = base.plusDays(if (!date1904 && days > 60) days - 1 else days).atStartOfDay().plusSeconds(seconds)
        return date.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))
    }

    fun write(entries: List<LedgerEntry>): ByteArray {
        require(entries.size <= 10000) { "表格导出最多 10,000 条，请使用完整备份" }
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val names = listOf("支出", "收入", "余额变更")
        val parts = linkedMapOf<String, String>()
        parts["[Content_Types].xml"] = "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" + (1..3).joinToString("") { "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>"
        parts["_rels/.rels"] = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$rel/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
        parts["xl/workbook.xml"] = "<workbook xmlns=\"$ns\" xmlns:r=\"$rel\"><sheets>" + names.mapIndexed { i, n -> "<sheet name=\"$n\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>"
        parts["xl/_rels/workbook.xml.rels"] = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + (1..3).joinToString("") { "<Relationship Id=\"rId$it\" Type=\"$rel/worksheet\" Target=\"worksheets/sheet$it.xml\"/>" } + "<Relationship Id=\"styles\" Type=\"$rel/styles\" Target=\"styles.xml\"/></Relationships>"
        parts["xl/styles.xml"] = "<styleSheet xmlns=\"$ns\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"2\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
        names.forEachIndexed { i, name ->
            val headers = SpreadsheetImport.headers(name) + SpreadsheetImport.giftHeaders
            val rows = listOf(headers) + entries.filter { it.deletedAt == null && it.type == EntryType.entries[i] }.map { e ->
                val date = e.origin?.originalDate?.takeIf { it.startsWith(e.occurredOn) } ?: e.occurredOn
                listOf(name, date, e.categoryL1, e.categoryL2, e.account) +
                    (if (i == 2) listOf(e.origin?.account2.orEmpty()) else emptyList()) +
                    listOf(e.currency, Money.format(e.amountMinor), e.member, e.merchant, e.origin?.projectCategory.orEmpty(), e.project, e.recordedBy, e.note, if (e.isGift) "是" else "否", e.counterparty)
            }
            val xml = buildString {
                append("<worksheet xmlns=\"$ns\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"14\" width=\"18\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"23\" customWidth=\"1\"/></cols><sheetData>")
                rows.forEachIndexed { ri, row ->
                    append("<row r=\"${ri + 1}\">")
                    row.forEachIndexed { ci, value ->
                        val ref = "${('A'.code + ci).toChar()}${ri + 1}"
                        if (ri > 0 && headers[ci] == "金额") append("<c r=\"$ref\" s=\"1\"><v>$value</v></c>")
                        else append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>")
                    }; append("</row>")
                }; append("</sheetData></worksheet>")
            }
            parts["xl/worksheets/sheet${i + 1}.xml"] = xml
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> parts.forEach { (path, xml) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml).toByteArray(Charsets.UTF_8)); zip.closeEntry()
        } }
        require(out.size() <= MAX_BYTES) { "导出表格超过 5 MB，请使用完整备份" }
        return out.toByteArray()
    }
    private fun escape(s: String): String = s.filter { it >= ' ' || it in "\n\r\t" }.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
