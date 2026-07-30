package com.rpgmaps.tabletop.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rpgmaps.tabletop.display.SinkStatus

/**
 * One line under the map title telling the DM where the players' view is
 * actually going. Worth the space: "is the TV seeing this?" is the question
 * asked most often mid-session, and a silent failure is the worst outcome.
 */
@Composable
fun DisplayStatusRow(
    statuses: List<SinkStatus>,
    modifier: Modifier = Modifier,
) {
    if (statuses.isEmpty()) return

    val transferring = statuses.firstOrNull { it.transferProgress != null }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        statuses.forEach { status ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dot(connected = status.connected)
                Text(
                    text = status.detail.ifBlank { status.label },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        transferring?.transferProgress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(width = 64.dp, height = 4.dp),
            )
        }
    }
}

@Composable
private fun Dot(connected: Boolean) {
    Row(
        Modifier
            .size(8.dp)
            .background(
                color = if (connected) Color(0xFF4ADE80) else Color(0xFF6B7280),
                shape = CircleShape,
            ),
    ) {}
}
