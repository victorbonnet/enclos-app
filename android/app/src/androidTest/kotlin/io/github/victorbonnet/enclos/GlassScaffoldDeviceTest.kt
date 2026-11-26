package io.github.victorbonnet.enclos

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The shared bars on a device (`GlassScaffold`): stripes pass under a top and a
 * bottom bar and the pixels in the bars are read back from a screenshot. It
 * claims what a bar does to what is behind it, on the platform that runs it: it
 * does not claim the app's own screens. Screenshots go to the target's external
 * files `glass/`, for `adb pull`; it holds synthetic stripes only.
 */
@RunWith(AndroidJUnit4::class)
class GlassScaffoldDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun launch(frost: Boolean, dark: Boolean = false): MainActivity {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.runOnMainSync { GlassStripes.show(activity, frost, dark) }
        val end = SystemClock.uptimeMillis() + 10_000
        while (GlassStripes.topPx == 0 && SystemClock.uptimeMillis() < end) SystemClock.sleep(50)
        SystemClock.sleep(600)
        return activity
    }

    private fun shot(name: String): Bitmap {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "glass").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun rgb(bitmap: Bitmap, x: Int, y: Int): Triple<Int, Int, Int> {
        val c = bitmap.getPixel(x, y)
        return Triple((c shr 16) and 255, (c shr 8) and 255, c and 255)
    }

    /** The largest step between two vertically neighbouring pixels in column [x], rows [from, to). */
    private fun roughest(bitmap: Bitmap, x: Int, from: Int, to: Int): Int =
        (from until to - 1).maxOf { y ->
            val a = rgb(bitmap, x, y); val b = rgb(bitmap, x, y + 1)
            maxOf(abs(a.first - b.first), abs(a.second - b.second), abs(a.third - b.third))
        }

    private fun move(px: Int) {
        instrumentation.runOnMainSync { GlassStripes.shift.intValue = px }
    }

    @Test
    fun frost_blurs_what_passes_under_the_bars_and_follows_it() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "SETUP: frost needs Android 12 or later" }
        val activity = launch(frost = true)
        val bar = GlassStripes.topPx
        val x = activity.resources.displayMetrics.widthPixels / 2
        val stripe = (40 * activity.resources.displayMetrics.density).toInt()
        val before = shot("frost-0")
        // Under the bar the stripes are blurred and tinted: no hard step, and light (bg at .76 over the blur).
        assertTrue("bar interior is smooth: ${roughest(before, x, 2, bar - 2)}", roughest(before, x, 2, bar - 2) < 12)
        val under = rgb(before, x, bar - 6)
        assertTrue("bar is tinted light: $under", under.first > 150 && under.second > 150)
        // Below the bar the same stripes are sharp.
        assertTrue("content is sharp below the bar", roughest(before, x, bar + 4, bar + 2 * stripe) > 150)
        // The stripes move: the bar's pixels follow them, which a stale copy would not.
        move(stripe)
        val after = shot("frost-1")
        val a = rgb(before, x, bar - 6); val b = rgb(after, x, bar - 6)
        assertTrue("the bar follows what passes under it: $a -> $b", abs(a.first - b.first) > 8)
        // The bottom bar is glass too: no hard step in it.
        val h = before.height
        // Off-centre: the gesture pill of the navigation bar sits mid-width and is the system's, not the bar's.
        val side = activity.resources.displayMetrics.widthPixels / 4
        val low = roughest(before, side, h - GlassStripes.bottomPx + 8, h - 8)
        assertTrue("bottom bar is smooth: $low", low < 12)
        // It is tinted light, not an opaque or untinted bar, and it follows the stripes like the top bar does.
        val y = h - GlassStripes.bottomPx / 2 - 8
        val lowBefore = rgb(before, side, y)
        assertTrue("bottom bar is tinted light: $lowBefore", lowBefore.first > 150 && lowBefore.second > 150)
        // The blur flattens the stripes, so the swing at one row depends on the phase: take the largest over the bar.
        val rows = (h - GlassStripes.bottomPx + 8) until (h - 8)
        val swing = rows.maxOf { r -> abs(rgb(before, side, r).first - rgb(after, side, r).first) }
        assertTrue("the bottom bar follows what passes under it: largest change $swing", swing > 8)
        activity.finish()
    }

    @Test
    fun veil_is_opaque_with_a_fade_and_never_blurs() {
        val activity = launch(frost = false)
        val bar = GlassStripes.topPx
        val x = activity.resources.displayMetrics.widthPixels / 2
        val fade = (20 * activity.resources.displayMetrics.density).toInt()
        val before = shot("veil-0")
        val edge = bar - fade
        assertEquals("the bar is bg, opaque", Triple(250, 250, 248), rgb(before, x, edge - 6))
        // Over its 20 dp edge the content fades in from the bar's colour: not yet black at the top of it.
        val soft = rgb(before, x, edge + fade / 4)
        assertTrue("the fade is still mostly bar: $soft", soft.second > 100)
        activity.finish()
    }
}
