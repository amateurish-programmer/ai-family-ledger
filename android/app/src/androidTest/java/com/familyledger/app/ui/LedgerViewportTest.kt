package com.familyledger.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.familyledger.app.data.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Paint checks catch out-of-bounds drawing that non-overlapping layout rectangles cannot. */
class LedgerViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contentCannotPaintIntoTheStatusBarOrBottomNavigationInsets() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                FamilyLedgerTheme {
                    Box(Modifier.requiredSize(200.dp, 240.dp).background(Color.Green).testTag("ledger_viewport_probe")) {
                        LedgerViewport(PaddingValues(top = 30.dp, bottom = 40.dp)) {
                            Box(Modifier.fillMaxSize().drawBehind {
                                drawRect(Color.Red, Offset(0f, -100f), Size(size.width, size.height + 200f))
                            })
                        }
                    }
                }
            }
        }
        val pixels = compose.onNodeWithTag("ledger_viewport_probe").captureToImage().toPixelMap()
        assertEquals(Color.Green, pixels[100, 15])
        assertEquals(Color.Red, pixels[100, 100])
        assertEquals(Color.Green, pixels[100, 225])
    }
}
