package io.github.victorbonnet.enclos

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.AppBar
import io.github.victorbonnet.enclos.ui.EnclosTheme
import io.github.victorbonnet.enclos.ui.GlassScaffold

/**
 * Black and red stripes 40 dp tall, run under a top and a bottom bar of the
 * shared [GlassScaffold], laid full screen over the real Activity, so a device
 * can show what a bar does to what passes under it. [shift] moves the stripes
 * (px). Test support only.
 */
object GlassStripes {
    val shift = mutableIntStateOf(0)
    val band = mutableStateOf(Color(0, 0, 255))
    @Volatile var topPx = 0
    @Volatile var bottomPx = 0

    fun show(activity: MainActivity, frost: Boolean, dark: Boolean) {
        shift.intValue = 0
        band.value = Color(0, 0, 255)
        topPx = 0
        val view = ComposeView(activity).apply {
            setContent {
                EnclosTheme(dark = dark) {
                    GlassScaffold(
                        frost = frost,
                        top = { AppBar { Box(Modifier.height(64.dp)) } },
                        bottom = { Box(Modifier.fillMaxWidth().height(64.dp)) },
                    ) { room ->
                        topPx = with(LocalDensity.current) { room.calculateTopPadding().roundToPx() }
                        bottomPx = with(LocalDensity.current) { room.calculateBottomPadding().roundToPx() }
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().offset { IntOffset(0, shift.intValue) }) {
                                repeat(40) { Box(Modifier.fillMaxWidth().height(40.dp).background(if (it % 2 == 0) Color.Black else Color.Red)) }
                            }
                            // A solid band over the last 200 dp, in a colour the stripes never use: the bottom bar's
                            // hue can only come from its own backdrop, so a blur taken from the wrong place shows.
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp).background(band.value))
                        }
                    }
                }
            }
        }
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
