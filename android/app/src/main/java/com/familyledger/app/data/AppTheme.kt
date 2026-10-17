package com.familyledger.app.data

/** Stable device preference, deliberately separate from account and ledger data. */
enum class AppTheme(val storageId: String, val label: String, val description: String) {
    WARM("warm", "奶油暖居", "暖橙与奶油色，把日子记成家的模样"),
    JOURNAL("journal", "手账生活风", "纸张、便签与墨色，留住生活的小确幸"),
    FINANCE("finance", "简洁财务风", "清爽青灰与紧凑排版，专注每一笔收支");

    companion object {
        fun fromStored(value: String?): AppTheme = entries.firstOrNull { it.storageId == value } ?: WARM
    }
}
