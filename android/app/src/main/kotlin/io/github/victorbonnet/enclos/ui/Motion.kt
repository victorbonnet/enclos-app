package io.github.victorbonnet.enclos.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The app's motion: short, and only to show where something came from or
 * went. Fast enough never to be waited on (Material's short and medium
 * durations), eased so nothing starts or stops abruptly.
 */
object Motion {
    /** Something small changing state: a colour, a badge, a tab's corners. */
    const val SHORT = 150
    /** A screen or a message arriving. */
    const val MEDIUM = 250
    /** What leaves goes faster than what arrives. */
    const val EXIT = 150

    /** Arriving: quick start, long gentle stop (Material's emphasized decelerate). */
    val decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Leaving: gentle start, quick finish (Material's emphasized accelerate). */
    val accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Changing in place. */
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> enter(duration: Int = MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, decelerate)
    fun <T> exit(duration: Int = EXIT): FiniteAnimationSpec<T> = tween(duration, easing = accelerate)
    fun <T> change(duration: Int = SHORT): FiniteAnimationSpec<T> = tween(duration, easing = standard)
}

/**
 * Whether the person turned animations off (Settings, Accessibility,
 * Remove animations): Compose then ends transitions at once, and a looping
 * animation such as the working indicator stands still instead.
 */
@Composable
fun animationsOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
