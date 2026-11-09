package io.github.victorbonnet.enclos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview

/** The Inbox's independent filters, kept together so both can be changed at once. */
@Composable
fun ConversationFilters(
    unreadOnly: Boolean,
    archivedOnly: Boolean,
    onUnreadChange: (Boolean) -> Unit,
    onArchivedChange: (Boolean) -> Unit,
) {
    val p = palette
    var expanded by remember { mutableStateOf(false) }
    val count = (if (unreadOnly) 1 else 0) + (if (archivedOnly) 1 else 0)
    val active = count > 0
    Box {
        Box(
            Modifier.size(48.dp).clip(Shapes.control)
                .background(if (active) p.accent.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (active) p.accent else Color.Transparent, Shapes.control)
                .clickable(onClickLabel = "Show conversation filters", role = Role.Button) { expanded = true }
                .semantics {
                    contentDescription = "Conversation filters"
                    stateDescription = when (count) {
                        0 -> "No filters active"
                        1 -> "1 filter active"
                        else -> "$count filters active"
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Glyph(Glyphs.filter, if (active) p.accent else p.textMuted) }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.background(p.bg)) {
            FilterToggle("Unread only", unreadOnly, onUnreadChange)
            FilterToggle("Archived conversations", archivedOnly, onArchivedChange)
        }
    }
}

/** The whole row is one accessible switch; the thumb is only its visual state. */
@Composable
private fun FilterToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.width(304.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, color = palette.text, style = Type.small, modifier = Modifier.weight(1f))
        AppSwitch(checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun ConversationFiltersPreview() {
    var unreadOnly by remember { mutableStateOf(false) }
    var archivedOnly by remember { mutableStateOf(false) }
    EnclosTheme {
        Row(
            Modifier.background(palette.bg).statusBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Inbox", style = Type.heading, color = palette.text)
            Spacer(Modifier.weight(1f))
            ConversationFilters(unreadOnly, archivedOnly, { unreadOnly = it }, { archivedOnly = it })
        }
    }
}
