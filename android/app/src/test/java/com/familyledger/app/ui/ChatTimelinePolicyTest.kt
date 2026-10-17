package com.familyledger.app.ui

import org.junit.Assert.*
import org.junit.Test

class ChatTimelinePolicyTest {
    private val messages = (1..80).map { "message-$it" }

    @Test fun firstVisitOnlyIncludesTheMostRecentThirtyMessages() {
        assertEquals(50, chatWindowStart(messages, null))
        assertEquals(0, chatWindowStart(messages.take(12), null))
        assertEquals(0, chatWindowStart(emptyList(), null))
    }

    @Test fun eachManualLoadAddsAtMostThirtyEarlierMessages() {
        assertEquals("message-21", earlierChatWindowStart(messages, "message-51"))
        assertEquals("message-1", earlierChatWindowStart(messages, "message-21"))
        assertEquals("message-1", earlierChatWindowStart(messages, "message-1"))
        assertNull(earlierChatWindowStart(emptyList(), null))
    }

    @Test fun newRepliesDoNotEvictTheOldestLoadedMessageOrLoadEarlierHistory() {
        assertEquals(50, chatWindowStart(messages + "message-81", "message-51"))
        assertEquals(51, chatWindowStart(listOf("older") + messages, "message-51"))
    }

    @Test fun missingWindowBoundaryFallsBackToRecentPage() {
        assertEquals(50, chatWindowStart(messages, "removed-message"))
    }

    @Test fun unchangedMessagesNeverRequestAScroll() {
        assertFalse(shouldFollowChatMessage("last", "last", latestIsUser = true, nearLatest = true))
        assertFalse(shouldFollowChatMessage(null, null, latestIsUser = false, nearLatest = true))
    }

    @Test fun assistantReplyDoesNotTakeTheReaderAwayFromOlderMessages() {
        assertFalse(shouldFollowChatMessage("previous", "reply", latestIsUser = false, nearLatest = false))
        assertTrue(shouldFollowChatMessage("previous", "reply", latestIsUser = false, nearLatest = true))
    }

    @Test fun sendingAMessageReturnsToTheLatestConversation() {
        assertTrue(shouldFollowChatMessage("previous", "sent", latestIsUser = true, nearLatest = false))
    }
}
