package io.github.victorbonnet.enclos.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The enclos design system's colour tokens (its `tokens.json`). Both themes
 * share every name; screens use these, never raw colours.
 */
@Immutable
data class Palette(
    /** Screen background. */
    val bg: Color,
    /** Bars, cards, incoming bubbles. */
    val surface: Color,
    val text: Color,
    /** Secondary copy, message previews. */
    val textMuted: Color,
    /** Metadata in mono: times, addresses. */
    val textFaint: Color,
    /** Hairlines: dividers and borders, never shadows. */
    val line: Color,
    /** Field outlines, inactive avatar corners. */
    val lineStrong: Color,
    /** Links, unread, the active tab, focus. */
    val accent: Color,
    /** The pairing viewfinder and the terminal prompt. */
    val panelDeep: Color,
    val panelText: Color,
    val panelMuted: Color,
    val lime: Color,
    /** The one primary action, outgoing bubbles. */
    val buttonBg: Color,
    val buttonText: Color,
)

val LightPalette = Palette(
    bg = Color(0xFFFAFAF8),
    surface = Color(0xFFF2F3EF),
    text = Color(0xFF1B1C1E),
    textMuted = Color(0xFF45484D),
    textFaint = Color(0xFF5D6066),
    line = Color(0xFFE0E2DC),
    lineStrong = Color(0xFFBFC4B8),
    accent = Color(0xFF3D6A29),
    panelDeep = Color(0xFF10291F),
    panelText = Color(0xFFEEF3E4),
    panelMuted = Color(0xFFC0D1B5),
    lime = Color(0xFFD5E6AF),
    buttonBg = Color(0xFF173C31),
    buttonText = Color(0xFFFAFAF8),
)

val DarkPalette = Palette(
    bg = Color(0xFF0B1A14),
    surface = Color(0xFF10291F),
    text = Color(0xFFEEF3E4),
    textMuted = Color(0xFFC0D1B5),
    textFaint = Color(0xFF93A891),
    line = Color(0xFF1F3A2D),
    lineStrong = Color(0xFF4A6B55),
    accent = Color(0xFFA9CC7A),
    panelDeep = Color(0xFF06120D),
    panelText = Color(0xFFEEF3E4),
    panelMuted = Color(0xFFC0D1B5),
    lime = Color(0xFFD5E6AF),
    buttonBg = Color(0xFFD5E6AF),
    buttonText = Color(0xFF10291F),
)

/** The system's type styles the app uses: one system sans, mono for what a machine would print. */
object Type {
    val mono = FontFamily.Monospace

    /** Screen titles (`title-large`). */
    val titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.03).em)
    /** App bar titles in a conversation (`heading`). */
    val heading = TextStyle(fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em)
    /** Message text. */
    val message = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 25.sp)
    /** Row titles. */
    val name = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    /** Buttons and labels (`label`). */
    val label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    /** Previews and secondary lines (`small`). */
    val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    /** Addresses, times, the Today notes (`code`, here at the apps' 12px). */
    val code = TextStyle(fontFamily = mono, fontSize = 12.sp, lineHeight = 16.sp)
    /** Times in bubbles, chips, day separators. */
    val meta = TextStyle(fontFamily = mono, fontSize = 11.sp, lineHeight = 14.sp)
}

/** The apps' shapes: soft bubbles and controls, with the logo's square corner kept on the speaker's side. */
object Shapes {
    val bubbleIn = RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
    val bubbleOut = RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
    val bubble = RoundedCornerShape(16.dp)
    val control = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(50)
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** The current theme's palette. */
val palette: Palette
    @Composable get() = LocalPalette.current

/** Light or dark: the system's (the default) or the person's choice, kept on the device. */
enum class ThemeChoice {
    System, Light, Dark;

    /** Whether this choice is dark, the system being [systemDark]. */
    fun dark(systemDark: Boolean) = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        fun read(saved: String?): ThemeChoice = entries.firstOrNull { it.name == saved } ?: System
    }
}

/**
 * Tells Android the app's choice (Android 12 and later), which keeps it:
 * the window the app opens in and the system bars then match it before
 * the first frame is drawn. Earlier releases have the choice in the app only.
 */
fun applyTheme(context: Context, choice: ThemeChoice) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val mode = when (choice) {
        ThemeChoice.System -> UiModeManager.MODE_NIGHT_AUTO
        ThemeChoice.Light -> UiModeManager.MODE_NIGHT_NO
        ThemeChoice.Dark -> UiModeManager.MODE_NIGHT_YES
    }
    context.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
}

/** [target]'s colours, each moving to its new value when the theme changes: the theme crossfades. */
@Composable
private fun animated(target: Palette): Palette {
    @Composable
    fun Color.moving(label: String): Color = animateColorAsState(this, Motion.change(Motion.MEDIUM), label = label).value
    return Palette(
        bg = target.bg.moving("bg"),
        surface = target.surface.moving("surface"),
        text = target.text.moving("text"),
        textMuted = target.textMuted.moving("textMuted"),
        textFaint = target.textFaint.moving("textFaint"),
        line = target.line.moving("line"),
        lineStrong = target.lineStrong.moving("lineStrong"),
        accent = target.accent.moving("accent"),
        panelDeep = target.panelDeep.moving("panelDeep"),
        panelText = target.panelText.moving("panelText"),
        panelMuted = target.panelMuted.moving("panelMuted"),
        lime = target.lime.moving("lime"),
        buttonBg = target.buttonBg.moving("buttonBg"),
        buttonText = target.buttonText.moving("buttonText"),
    )
}

/** The enclos theme: light or dark, Material components tinted with the tokens. */
@Composable
fun EnclosTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = animated(if (dark) DarkPalette else LightPalette)
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.buttonBg,
        onPrimary = p.buttonText,
        secondary = p.accent,
        onSecondary = p.bg,
        background = p.bg,
        onBackground = p.text,
        surface = p.bg,
        onSurface = p.text,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.textMuted,
        surfaceContainer = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.surface,
        outline = p.lineStrong,
        outlineVariant = p.line,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
