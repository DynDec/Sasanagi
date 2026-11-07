package com.sasayaki.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sasayaki.player.AppGraph

@Composable
fun MiniPlayer(graph: AppGraph, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val engine = graph.engine
    val s by engine.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val entry = s.entry ?: return
    val version = s.version ?: return
    val position by rememberPosition(engine, 500)
    val duration by rememberDuration(engine)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { duration?.let { (position.toFloat() / it).coerceIn(0f, 1f) } ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(entry.coverFor(settings), Modifier.size(48.dp), corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.track?.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val seg = if (version.tracks.size > 1) "${s.index + 1}/${version.tracks.size} · " else ""
                    Text(
                        seg + entry.title, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { engine.togglePlay() }) {
                    Icon(if (s.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playWhenReady) "Pause" else "Play")
                }
                if (s.playWhenReady) {
                    IconButton(onClick = { engine.next() }, enabled = s.index < version.tracks.lastIndex) { Icon(Icons.Rounded.SkipNext, "Next segment") }
                } else {
                    IconButton(onClick = { engine.stop() }) { Icon(Icons.Rounded.Close, "Stop") }
                }
            }
        }
    }
}
