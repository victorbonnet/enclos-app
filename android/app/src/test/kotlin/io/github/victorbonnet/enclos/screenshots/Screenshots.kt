package io.github.victorbonnet.enclos.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.victorbonnet.enclos.ui.EnclosTheme
import org.junit.Assume.assumeTrue
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The phones a design screen is drawn on. Robolectric's qualifiers give the
 * window's size and density; the ratio is the one of two real phones.
 */
enum class Phone(val qualifiers: String) {
    /** 360 × 640 dp at 3×: a small phone. */
    Small("w360dp-h640dp-xxhdpi"),

    /** 412 × 915 dp at 3×: a current large phone. */
    Large("w412dp-h915dp-xxhdpi"),
}

/** How a screen is drawn: the phone, the theme and the person's font size. */
data class Look(val phone: Phone, val dark: Boolean, val fontScale: Float = 1f) {
    /** `light-small`, `dark-large`, `light-small-font-1.3`: the PNG's name. */
    val name: String
        get() = buildString {
            append(if (dark) "dark" else "light")
            append('-').append(phone.name.lowercase())
            if (fontScale != 1f) append("-font-").append(fontScale)
        }
}

/**
 * The folder `just screenshots` draws into (build/screenshots, which git
 * ignores), or null when the tests run for what they check: a design screen
 * then skips itself, so a normal test run writes no image.
 */
internal fun screenshotFolder(): File? = System.getProperty("enclos.screenshots.dir")?.let(::File)

/**
 * Draws [content] in the app's real [EnclosTheme], as [look] says, to
 * `<folder>/<screen>/<screen>-<look>.png`, and returns the file.
 *
 * Call it from a Robolectric test running with native graphics
 * (`@GraphicsMode(GraphicsMode.Mode.NATIVE)`): that is what draws pixels.
 * [content] must not read the clock or anything else that changes between
 * runs, or the PNG will not be the same twice.
 */
internal fun ComposeContentTestRule.drawn(screen: String, look: Look, content: @Composable () -> Unit): File {
    val folder = screenshotFolder()
    assumeTrue("run through `just screenshots`", folder != null)
    RuntimeEnvironment.setQualifiers("+${look.phone.qualifiers}")
    RuntimeEnvironment.setFontScale(look.fontScale)
    setContent { EnclosTheme(dark = look.dark) { content() } }
    waitForIdle()
    val file = File(folder, "$screen/$screen-${look.name}.png")
    file.parentFile!!.mkdirs()
    onRoot().captureRoboImage(file.path)
    return file
}
