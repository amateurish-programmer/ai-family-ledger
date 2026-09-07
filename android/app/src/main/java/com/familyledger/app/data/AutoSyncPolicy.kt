package com.familyledger.app.data

object AutoSyncPolicy {
    const val MIN_INTERVAL_MILLIS = 300_000L
    fun isDue(lastSuccessMillis: Long, nowMillis: Long): Boolean =
        lastSuccessMillis <= 0 || nowMillis < lastSuccessMillis || nowMillis - lastSuccessMillis >= MIN_INTERVAL_MILLIS
}
