package com.familyledger.app.ui

private const val CHAT_PAGE_SIZE = 30

/** Keep the loaded boundary stable when new messages arrive at either end. */
internal fun chatWindowStart(messageIds: List<String>, firstLoadedId: String?): Int {
    val savedStart = firstLoadedId?.let(messageIds::indexOf) ?: -1
    return if (savedStart >= 0) savedStart else (messageIds.size - CHAT_PAGE_SIZE).coerceAtLeast(0)
}

internal fun earlierChatWindowStart(messageIds: List<String>, firstLoadedId: String?): String? =
    messageIds.getOrNull((chatWindowStart(messageIds, firstLoadedId) - CHAT_PAGE_SIZE).coerceAtLeast(0))

internal fun shouldFollowChatMessage(previousId: String?, latestId: String?, latestIsUser: Boolean, nearLatest: Boolean): Boolean =
    latestId != null && latestId != previousId && (latestIsUser || nearLatest)
