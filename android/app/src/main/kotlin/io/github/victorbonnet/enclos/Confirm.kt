package io.github.victorbonnet.enclos

/**
 * A decision that changes what an agent may do (approving or denying a
 * policy request, saving a policy edit) asks for the phone's biometric or
 * screen lock first, every time, whatever the app-lock setting: a phone
 * left unlocked in someone else's hands does not widen an agent's access.
 * The prompt is the app lock's ([Unlocker]); this is the rule around it,
 * apart from Android so it is tested on the JVM.
 */
object Confirm {
    /** Why nothing is asked on a phone without a screen lock: there is nothing to prove it is the person with. */
    const val NO_SCREEN_LOCK = "Set a screen lock on this phone first: a PIN, a pattern or a password."

    /**
     * Asks through [ask] (the prompt titled with its first argument, whose
     * answer is whether it was them, and why not) and runs [send] only once
     * the person proved it is them. Cancelled or failed, nothing is sent and
     * [held] gets the system's reason. Without a [screenLock] on the phone,
     * nothing is asked or sent.
     */
    fun decide(
        title: String,
        screenLock: Boolean,
        ask: (String, (Boolean, String?) -> Unit) -> Unit,
        send: () -> Unit,
        held: (String?) -> Unit,
    ) {
        if (!screenLock) {
            held(NO_SCREEN_LOCK)
            return
        }
        ask(title) { ok, why -> if (ok) send() else held(why) }
    }
}
