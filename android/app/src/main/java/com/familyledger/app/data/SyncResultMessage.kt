package com.familyledger.app.data

fun SyncResult.compactMessage(): String? = listOfNotNull(
    uploaded.takeIf { it > 0 }?.let { "上传 $it 条" },
    downloaded.takeIf { it > 0 }?.let { "下载 $it 条" },
    conflicts.size.takeIf { it > 0 }?.let { "冲突 $it 条" },
).takeIf { it.isNotEmpty() }?.joinToString(" / ")
