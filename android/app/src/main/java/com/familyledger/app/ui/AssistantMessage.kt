package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Only basic text formatting is recognized; model output never executes HTML or links. */
internal fun assistantInlineText(text: String): AnnotatedString = buildAnnotatedString {
    val matches = Regex("\\*\\*(.+?)\\*\\*").findAll(text)
    var start = 0
    matches.forEach { match ->
        append(text.substring(start, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
        start = match.range.last + 1
    }
    append(text.substring(start))
}

@Composable internal fun AssistantMessage(text: String, modifier: Modifier = Modifier) {
    val paragraphs = remember(text) { text.split('\n') }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            paragraphs.forEach { line ->
                val heading = line.matches(Regex("^#{1,4} .+"))
                val content = if (heading) line.substringAfter(' ') else if (line.startsWith("- ") || line.startsWith("* ")) "• ${line.drop(2)}" else line
                if (content.isBlank()) Spacer(Modifier.height(5.dp))
                else Text(remember(content) { assistantInlineText(content) }, style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    modifier = if (heading) Modifier.padding(top = 6.dp) else Modifier)
            }
        }
    }
}
