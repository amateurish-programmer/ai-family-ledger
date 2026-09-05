package com.familyledger.app.domain

data class ImportOrigin(
    val fileHash: String, val fileName: String, val sheet: String, val rowNumber: Int,
    val importedAt: Long, val originalDate: String, val account2: String = "",
    val projectCategory: String = "", val rawFields: Map<String, String> = emptyMap()
)
enum class ImportStatus(val label: String) { NEW("新增"), SUSPECTED("疑似重复"), EXISTING("已处理"), ERROR("错误") }
data class ImportRow(val sheet: String, val rowNumber: Int, val entry: LedgerEntry?, val status: ImportStatus, val error: String = "") {
    val key: String get() = "$sheet:$rowNumber"
}
data class ImportPreview(val fileName: String, val rows: List<ImportRow>, val ignoredSheets: List<String> = emptyList())
data class ImportBatch(val id: String, val fileName: String, val importedAt: Long, val total: Int, val active: Int)
