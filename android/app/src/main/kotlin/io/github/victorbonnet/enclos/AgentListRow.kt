package io.github.victorbonnet.enclos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.victorbonnet.enclos.ui.*

/** The Agents list and sandbox pages share the same row. */
@Composable
internal fun AgentListRow(agent: Agent, busy: Boolean, status: String, usage: String?, needsYou: Int, onClick: () -> Unit) {
    val p = palette
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${agent.label}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AgentInitial(agent.label, busy, side = 32.dp, photo = agent.photoKey)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(agent.label, style = Type.name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Glyph(Glyphs.chevron, p.textFaint, size = 16.dp)
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) WorkingWave(square = 4.dp, lift = 3.dp)
                Text(
                    buildAnnotatedString {
                        if (needsYou > 0) {
                            append("$needsYou needs you")
                            addStyle(SpanStyle(color = p.accent), 0, length)
                            append(" · ")
                        }
                        val start = length
                        append(status)
                        if (busy) addStyle(SpanStyle(color = p.accent), start, length)
                    },
                    style = Type.small, color = p.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            usage?.let {
                Text(it, style = Type.meta, color = p.textFaint, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
