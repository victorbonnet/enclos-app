package io.github.victorbonnet.enclos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/**
 * Locked, [lockScreen] is all that is composed: no screen, dialog, sheet or
 * menu of [screens] is there to see or reach, by touch, keyboard or
 * accessibility services (a dialog is a window of its own, which covering
 * the screens would leave above the lock), and none marks a message read.
 * Unlocked, [screens] come back where they were: their saved state (the
 * route, the tab, scroll positions) is kept meanwhile.
 */
@Composable
fun LockGate(locked: Boolean, lockScreen: @Composable () -> Unit, screens: @Composable () -> Unit) {
    val kept = rememberSaveableStateHolder()
    if (locked) lockScreen() else kept.SaveableStateProvider("screens", screens)
}
