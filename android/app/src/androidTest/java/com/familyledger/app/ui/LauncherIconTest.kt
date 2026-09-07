package com.familyledger.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.familyledger.app.R

class LauncherIconTest {
    @get:Rule val compose = createComposeRule()
    @Test fun launcherResolvesAdaptiveLayersAndRendersAtLauncherSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        compose.setContent { AndroidView(factory = { ctx -> object : View(ctx) {
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(255, 249, 238))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(82, 91, 71); textSize = 30f }
                canvas.drawText("Family Ledger · v1.2.0", 40f, 80f, paint)
                for ((index, size) in listOf(216, 144, 96, 48).withIndex()) {
                    val left = 50; val top = 125 + index * 270
                    icon.setBounds(left, top, left + size, top + size); icon.draw(canvas)
                    val x = left + 285
                    val bounds = RectF(x.toFloat(), top.toFloat(), (x + size).toFloat(), (top + size).toFloat())
                    val mask = Path().apply { addRoundRect(bounds, size * .24f, size * .24f, Path.Direction.CW) }
                    canvas.save(); canvas.clipPath(mask)
                    for (resource in listOf(R.drawable.ic_ledger_background, R.drawable.ic_ledger_foreground)) {
                        val layer = context.getDrawable(resource)!!
                        val pad = size / 4
                        layer.setBounds(x - pad, top - pad, x + size + pad, top + size + pad); layer.draw(canvas)
                    }
                    canvas.restore()
                }
            }
        } }) }
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/ledger-screens/launcher-v120.png")
        ParcelFileDescriptor.AutoCloseInputStream(screenshot).use { it.readBytes() }
    }
}
