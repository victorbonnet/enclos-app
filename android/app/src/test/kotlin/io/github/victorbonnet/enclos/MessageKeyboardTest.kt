package io.github.victorbonnet.enclos

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.github.victorbonnet.enclos.ui.MessageKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The EditorInfo criterion (A1-A4) is asserted in the instrumented suite
// (MessageKeyboardEditorInfoTest); this is the value half, and it is not device proof.
class MessageKeyboardTest {
    @Test
    fun message_keyboard_asks_for_a_short_message_field_with_sentence_capitals_and_no_action() {
        assertEquals(KeyboardType.ShortMessage, MessageKeyboard.keyboardType)
        assertEquals(KeyboardCapitalization.Sentences, MessageKeyboard.capitalization)
        // Exactly the default: the change sets no IME action, so ↵ stays a new line.
        assertEquals(KeyboardOptions.Default.imeAction, MessageKeyboard.imeAction)
        // Left unset on purpose: the platform's own auto-correction, as at 97b904b.
        assertNull(MessageKeyboard.autoCorrectEnabled)
    }
}
