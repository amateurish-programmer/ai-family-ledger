package com.familyledger.app.ui

import org.junit.Assert.*
import org.junit.Test

class AssistantMessageTest {
    @Test fun formatsBoldWithoutChangingAmountsOrDroppingPlainText() {
        val text = assistantInlineText("收入 **5,450.00**，支出 **13.00**。")
        assertEquals("收入 5,450.00，支出 13.00。", text.text)
        assertEquals(2, text.spanStyles.size)
    }
    @Test fun incompleteMarkupAndHtmlRemainLiteralText() {
        val source = "**未结束 <script>text</script> ¥0.01"
        assertEquals(source, assistantInlineText(source).text)
    }
}
