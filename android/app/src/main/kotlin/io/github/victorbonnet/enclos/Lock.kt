package io.github.victorbonnet.enclos

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The app lock: the app asks for the person's fingerprint or face, or the
 * phone's own PIN, pattern or password, when it opens, and when it comes
 * back after [AWAY_MS] or more in the background. It hides the app; it
 * does not encrypt what the phone keeps.
 */
object AppLock {
    /** How long the app may be away before it locks again on its return. */
    const val AWAY_MS = 60_000L

    /** Whether the app locks as it comes back to the foreground, away since [leftAt] (elapsed time), now [now]. */
    fun locksOnReturn(on: Boolean, leftAt: Long?, now: Long): Boolean =
        on && leftAt != null && now - leftAt >= AWAY_MS

    /** Whether the phone has a screen lock (a PIN, pattern or password), which the app lock needs. */
    fun available(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
}

/**
 * Asks the person to prove it is them, the phone's own way: from Android
 * 11, a biometric (fingerprint, face) or the screen lock; on Android 10,
 * the same through its older call; on Android 9, the screen lock. Made
 * with the activity, before it starts, as Android requires for the
 * screen-lock page it may open.
 */
class Unlocker(private val activity: ComponentActivity) {
    private var waiting: ((Boolean, String?) -> Unit)? = null
    private val confirm: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            finish(result.resultCode == android.app.Activity.RESULT_OK, null)
        }

    /** Whether a prompt is up: the activity may stop meanwhile (the screen-lock page), which is not the person leaving. */
    val asking: Boolean get() = waiting != null

    /**
     * Shows the prompt titled [title]; [done] gets whether it was them, and
     * why not when the system says (cancelled, too many attempts).
     */
    fun ask(title: String, done: (Boolean, String?) -> Unit) {
        if (waiting != null) return
        waiting = done
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> biometric(title) {
                setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> biometric(title) {
                @Suppress("DEPRECATION")
                setDeviceCredentialAllowed(true)
            }
            else -> {
                val keyguard = activity.getSystemService(KeyguardManager::class.java)
                @Suppress("DEPRECATION")
                val intent = keyguard?.createConfirmDeviceCredentialIntent(title, null)
                if (intent == null) finish(false, "No screen lock on this phone.") else confirm.launch(intent)
            }
        }
    }

    private fun biometric(title: String, allow: BiometricPrompt.Builder.() -> Unit) {
        val prompt = BiometricPrompt.Builder(activity).setTitle(title).apply(allow).build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(true, null)

                // Cancelled, locked out, or no way to check: the system says which.
                override fun onAuthenticationError(code: Int, message: CharSequence) = finish(false, message.toString())
            },
        )
    }

    private fun finish(ok: Boolean, why: String?) {
        val done = waiting ?: return
        waiting = null
        done(ok, why)
    }
}
