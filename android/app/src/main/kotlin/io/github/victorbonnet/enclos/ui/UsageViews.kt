package io.github.victorbonnet.enclos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.UsageWindow

/**
 * What a turn's model calls used, one quiet line on the agent's side: under its answer, or
 * under the working bubble while it works (docs/spec.md, Usage).
 */
@Composable
fun UsageNote(text: String, modifier: Modifier = Modifier) {
    val p = palette
    Row(
        modifier.padding(start = 4.dp).semantics(mergeDescendants = true) { contentDescription = "Usage: $text" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Glyph(Glyphs.usage, p.textFaint, size = 14.dp)
        Text(text, style = Type.small, color = p.textFaint)
    }
}

/** The windows a host's usage can be read over, one selected. */
@Composable
fun UsageWindowTabs(selected: UsageWindow, enabled: Boolean, onSelect: (UsageWindow) -> Unit) {
    val p = palette
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsageWindow.entries.forEach { window ->
            val on = window == selected
            val shape = RoundedCornerShape(50)
            Text(
                window.label,
                style = Type.label,
                color = if (on) p.bg else p.textMuted,
                modifier = Modifier
                    .defaultMinSize(minHeight = 32.dp)
                    .semantics { this.selected = on }
                    .background(if (on) p.accent else p.bg, shape)
                    .border(1.dp, if (on) p.accent else p.textFaint, shape)
                    .clickable(enabled = enabled && !on, role = Role.Tab) { onSelect(window) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
