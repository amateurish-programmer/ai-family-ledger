package com.familyledger.app.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic fixtures only; deliberately independent of the production exporter. */
object SampleWorkbooks {
    val headers = listOf("交易类型", "日期", "一级分类", "二级分类", "支出账户", "账户币种", "金额", "成员", "商家", "项目分类", "项目", "记账人", "备注")
    val expense = listOf("支出", "2026-09-06 12:30:01", "食品酒水 ", "午餐", "银行卡", "CNY", "36.80", "家人", "测试餐馆", "生活", "日常", "本人", "合成记录\n第二行")
    fun book(rows: List<List<String>> = listOf(headers, expense), sheet: String = "支出", shared: Boolean = false, suffix: String = ""): ByteArray {
        val strings = rows.flatten().distinct()
        fun escaped(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val cells = rows.mapIndexed { ri, row -> "<row r=\"${ri + 1}\">" + row.mapIndexed { ci, value ->
            val ref = "${('A'.code + ci).toChar()}${ri + 1}"
            if (shared) "<c r=\"$ref\" t=\"s\"><v>${strings.indexOf(value)}</v></c>"
            else "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escaped(value)}</t></is></c>"
        }.joinToString("") + "</row>" }.joinToString("")
        return zip(mapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"$sheet\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"/></Relationships>",
            "xl/sharedStrings.xml" to "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">${strings.joinToString("") { "<si><t>${escaped(it)}</t></si>" }}</sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$cells</sheetData></worksheet>",
            "comment.txt" to suffix
        ))
    }
    fun zip(parts: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> parts.forEach { (path, content) ->
            zip.putNextEntry(ZipEntry(path).apply { time = 0 }); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        } }
    }.toByteArray()
}
