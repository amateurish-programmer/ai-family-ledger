package com.familyledger.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputPolicyTest {
    @Test fun syncKeepsChatInputEditableButDisablesSend() {
        val state = LedgerState(loading = false, conversationLoad = ConversationLoad.READY, busy = true, syncing = true, chatInput = "午餐13块")
        assertTrue(chatInputEnabled(state))
        assertFalse(chatSendEnabled(state))
    }

    @Test fun sendingDisablesBothInputAndSend() {
        val state = LedgerState(loading = false, conversationLoad = ConversationLoad.READY, busy = true, chatSending = true, chatInput = "午餐13块")
        assertFalse(chatInputEnabled(state))
        assertFalse(chatSendEnabled(state))
    }

    @Test fun idleWithTextEnablesBoth() {
        val state = LedgerState(loading = false, conversationLoad = ConversationLoad.READY, chatInput = "午餐13块")
        assertTrue(chatInputEnabled(state))
        assertTrue(chatSendEnabled(state))
    }

    @Test fun otherBusyOperationsStillLockChatInput() {
        val state = LedgerState(loading = false, conversationLoad = ConversationLoad.READY, busy = true, chatInput = "午餐13块")
        assertFalse(chatInputEnabled(state))
        assertFalse(chatSendEnabled(state))
    }

    @Test fun historyMustBeReadyBeforeInputAndSendEvenWhenLedgerIsLoaded() {
        listOf(ConversationLoad.LOADING, ConversationLoad.FAILED).forEach { load ->
            val state = LedgerState(loading = false, conversationLoad = load, chatInput = "午餐13块")
            assertFalse(chatInputEnabled(state))
            assertFalse(chatSendEnabled(state))
        }
    }
}
