package com.sasayaki.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BedtimeOff
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sasayaki.player.AppGraph
import com.sasayaki.player.ui.theme.CoverTinted
import com.sasayaki.player.data.Cover
import com.sasayaki.player.data.Cue
import com.sasayaki.player.data.Subtitles
import com.sasayaki.player.data.Version
import com.sasayaki.player.playback.EngineState
import com.sasayaki.player.playback.MAX_BOOST_DB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private enum class Sheet { SEGMENTS, SPEED, BOOST, SLEEP, VERSIONS }

@Composable
fun PlayerScreen(graph: AppGraph, onCollapse: () -> Unit, onOpenEntry: () -> Unit) {
    val s by graph.engine.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    CoverTinted(s.entry?.coverFor(settings), graph.covers, settings.coverTint) {
        PlayerContent(graph, onCollapse, onOpenEntry)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(graph: AppGraph, onCollapse: () -> Unit, onOpenEntry: () -> Unit) {
    val engine = graph.engine
    val s by engine.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val durationsMap by graph.durations.map.collectAsStateWithLifecycle()
    val entry = s.entry
    val version = s.version
    if (entry == null || version == null) {
        LaunchedEffect(Unit) { onCollapse() }
        return
    }
    val position by rememberPosition(engine)
    val playerDuration by rememberDuration(engine)
    val durations = remember(version, durationsMap) { version.tracks.map { durationsMap[it.ref.cacheKey] } }
    val trackDuration = playerDuration ?: durations.getOrNull(s.index)
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var showSubs by rememberSaveable { mutableStateOf(false) }
    val track = s.track
    // Height of WorkBar: 4dp padding, 20dp bar and a labelSmall time line.
    val workBarSpace = 24.dp + with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }

    val cues by produceState<List<Cue>>(emptyList(), track?.subtitle) {
        value = track?.subtitle?.let { withContext(Dispatchers.IO) { Subtitles.load(it) } } ?: emptyList()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse, modifier = Modifier.padding(start = 0.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse") }
                Text(
                    entry.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clickable(onClick = onOpenEntry),
                )
                IconButton(onClick = { sheet = Sheet.SEGMENTS }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Segments") }
            }

            // Whole-work bar, then artwork or subtitles. The cover stays centered and the bar
            // sits midway between the top bar and the cover.
            val workBar = settings.workBar && version.tracks.size > 1
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val subsShown = showSubs && cues.isNotEmpty()
                if (!subsShown) Spacer(Modifier.weight(1f))
                if (workBar) {
                    Box(Modifier.widthIn(max = 420.dp)) {
                        WorkBar(version, durations, s.index, position, onSeekTrack = { engine.seekToTrack(it) }, onSeekGlobal = { engine.seekGlobal(it) })
                    }
                    if (!subsShown) Spacer(Modifier.weight(1f))
                    Spacer(Modifier.height(8.dp))
                }
                AnimatedContent(subsShown, label = "art", modifier = if (subsShown) Modifier.weight(1f) else Modifier) { subs ->
                    if (subs) CuePanel(cues, position, onSeek = { engine.seekTo(it) })
                    else CoverImage(
                        entry.coverFor(settings, Cover.SIZE_LARGE),
                        Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f),
                        corner = 24.dp,
                    )
                }
                if (!subsShown) Spacer(Modifier.weight(if (workBar) 2f else 1f))
            }
            if (!showSubs && cues.isNotEmpty()) {
                val cue = cues.lastOrNull { position >= it.startMs && position < it.endMs }
                Text(
                    cue?.text ?: "", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                track?.title ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = { sheet = Sheet.VERSIONS },
                label = { Text(version.fullLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Layers, null, Modifier.size(18.dp)) },
                enabled = entry.versions.size > 1,
            )

            // The slider is centered in the space the work bar used to share with it, so the
            // title and chip stay put whether or not the bar is shown.
            Spacer(Modifier.height(8.dp + workBarSpace / 2))
            TrackSlider(position, trackDuration, s.buffering, onSeek = { engine.seekTo(it) })
            Spacer(Modifier.height(workBarSpace / 2 - 8.dp))
            Controls(s, settings.skipBackSec, settings.skipForwardSec, graph)

            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { sheet = Sheet.SPEED }) {
                    Icon(Icons.Rounded.Speed, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(formatSpeed(s.speed))
                }
                ToggleIconButton(s.boostDb > 0f, onClick = { sheet = Sheet.BOOST }) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Volume boost")
                }
                SleepButton(s, onClick = { sheet = Sheet.SLEEP })
                ToggleIconButton(s.repeatMode != Player.REPEAT_MODE_OFF, onClick = { engine.cycleRepeat() }) {
                    Icon(if (s.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat")
                }
                ToggleIconButton(showSubs && cues.isNotEmpty(), onClick = { showSubs = !showSubs }, enabled = cues.isNotEmpty()) {
                    Icon(Icons.Rounded.ClosedCaption, "Subtitles")
                }
            }
        }
    }

    when (sheet) {
        Sheet.SEGMENTS -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            SegmentList(version, durations, s.index, onPick = { engine.seekToTrack(it); sheet = null })
        }
        Sheet.SPEED -> ModalBottomSheet(onDismissRequest = { sheet = null }) { SpeedSheet(s.speed) { engine.setSpeed(it) } }
        Sheet.BOOST -> ModalBottomSheet(onDismissRequest = { sheet = null }) { BoostSheet(s.boostDb) { engine.setBoost(it) } }
        Sheet.SLEEP -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            SleepSheet(s, onMinutes = { engine.setSleepMinutes(it); sheet = null }, onEndOfTrack = { engine.setSleepEndOfTrack(); sheet = null },
                onCancel = { engine.cancelSleep(); sheet = null })
        }
        Sheet.VERSIONS -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Text("Switch version", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp)) {
                itemsIndexed(entry.versions) { _, v ->
                    val cur = v.id == version.id
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { if (!cur) engine.switchVersion(v); sheet = null },
                        leadingContent = { if (cur) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) else Spacer(Modifier.size(24.dp)) },
                        headlineContent = { Text(v.label, fontWeight = if (cur) FontWeight.SemiBold else null) },
                        supportingContent = { Text("${formatLabel(v.format)} · ${v.tracks.size} tracks" + if (v.isFilled) " · partial" else "") },
                    )
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun TrackSlider(position: Long, duration: Long?, buffering: Boolean, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val d = duration ?: 0L
    val value = if (dragging) dragValue else if (d > 0) (position.toFloat() / d).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                if (d > 0) onSeek((dragValue * d).toLong())
                dragging = false
            },
            enabled = d > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(if (dragging) (dragValue * d).toLong() else position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (buffering) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The whole work as one bar split into its segments; tap anywhere to jump there. */
@Composable
private fun WorkBar(version: Version, durations: List<Long?>, index: Int, position: Long, onSeekTrack: (Int) -> Unit, onSeekGlobal: (Long) -> Unit) {
    val known = durations.all { it != null && it > 0 }
    val weights = if (known) durations.map { it!!.toFloat() } else List(durations.size) { 1f }
    val total = weights.sum()
    val totalMs = if (known) durations.sumOf { it!! } else null
    val elapsed = if (known) durations.take(index).sumOf { it!! } + position else null
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val current = MaterialTheme.colorScheme.primaryContainer
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
            Canvas(
                Modifier.fillMaxSize().pointerInput(version, known, weights) {
                    detectTapGestures { o ->
                        val frac = (o.x / size.width).coerceIn(0f, 1f)
                        if (known && totalMs != null) onSeekGlobal((frac * totalMs).toLong())
                        else onSeekTrack((frac * weights.size).toInt().coerceIn(0, weights.lastIndex))
                    }
                },
            ) {
                val gap = 3.dp.toPx()
                val h = 6.dp.toPx()
                val y = (size.height - h) / 2
                val usable = size.width - gap * (weights.size - 1)
                var x = 0f
                weights.forEachIndexed { i, w ->
                    val segW = (usable * w / total).coerceAtLeast(1f)
                    val r = CornerRadius(h / 2)
                    drawRoundRect(if (i == index) current else track, Offset(x, y), Size(segW, h), r)
                    val fill = when {
                        i < index -> 1f
                        i == index -> durations[i]?.takeIf { it > 0 }?.let { (position.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
                        else -> 0f
                    }
                    if (fill > 0f) drawRoundRect(primary, Offset(x, y), Size(segW * fill, h), r)
                    x += segW + gap
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Text(
                if (elapsed != null) "${formatTime(elapsed)} / ${formatTime(totalMs)}" else "${index + 1} / ${durations.size}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Controls(s: EngineState, back: Int, fwd: Int, graph: AppGraph) {
    val engine = graph.engine
    val v = s.version
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { engine.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous segment", Modifier.size(32.dp)) }
        IconButton(onClick = { engine.skipBy(-back * 1000L) }) { Icon(backIcon(back), "Back $back seconds", Modifier.size(32.dp)) }
        FilledIconButton(
            onClick = { engine.togglePlay() },
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            Icon(if (s.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playWhenReady) "Pause" else "Play", Modifier.size(40.dp))
        }
        IconButton(onClick = { engine.skipBy(fwd * 1000L) }) { Icon(forwardIcon(fwd), "Forward $fwd seconds", Modifier.size(32.dp)) }
        IconButton(onClick = { engine.next() }, enabled = v != null && s.index < v.tracks.lastIndex) {
            Icon(Icons.Rounded.SkipNext, "Next segment", Modifier.size(32.dp))
        }
    }
}

private fun backIcon(sec: Int): ImageVector = when (sec) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.FastRewind
}

private fun forwardIcon(sec: Int): ImageVector = when (sec) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

/** An accent icon button that sits on a filled container while [active]. */
@Composable
private fun ToggleIconButton(active: Boolean, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (active) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
            content = content,
        )
    } else {
        IconButton(onClick = onClick, enabled = enabled, colors = IconButtonDefaults.iconButtonColors(contentColor = colors.primary), content = content)
    }
}

@Composable
private fun SleepButton(s: EngineState, onClick: () -> Unit) {
    val sleep = s.sleep
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleep) {
        while (sleep?.endsAt != null) { now = System.currentTimeMillis(); delay(1000) }
    }
    TextButton(onClick = onClick) {
        Icon(if (sleep != null) Icons.Rounded.Bedtime else Icons.Rounded.BedtimeOff, null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                sleep == null -> "Sleep"
                sleep.endOfTrack -> "End of segment"
                else -> formatTime(((sleep.endsAt ?: now) - now).coerceAtLeast(0))
            }
        )
    }
}

@Composable
private fun SegmentList(version: Version, durations: List<Long?>, index: Int, onPick: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
    Text(version.fullLabel(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(version.tracks) { i, t ->
            val cur = i == index
            ListItem(
                colors = ListItemDefaults.colors(containerColor = if (cur) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                modifier = Modifier.clickable { onPick(i) },
                leadingContent = {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        if (cur) Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                        else Text("${i + 1}", style = MaterialTheme.typography.labelLarge)
                    }
                },
                headlineContent = { Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (cur) FontWeight.SemiBold else null) },
                supportingContent = if (t.fromBase) { { Text("from “${version.baseLabel}”", style = MaterialTheme.typography.labelSmall) } } else null,
                trailingContent = { Text(formatTime(durations.getOrNull(i)), style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Playback speed", style = MaterialTheme.typography.titleMedium)
        Text(String.format(Locale.US, "%.2fx", speed), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 8.dp))
        Slider(value = speed, onValueChange = { onSpeed((it * 20).toInt() / 20f) }, valueRange = 0.5f..2f, steps = 29)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f, 0.9f, 1f, 1.25f, 1.5f).forEach { v ->
                FilterChip(selected = speed == v, onClick = { onSpeed(v) }, label = { Text(formatSpeed(v)) })
            }
        }
    }
}

@Composable
private fun BoostSheet(db: Float, onBoost: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Text("Volume boost", style = MaterialTheme.typography.titleMedium)
        Text(formatBoost(db), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 8.dp))
        Slider(value = db, onValueChange = { onBoost((it * 2).roundToInt() / 2f) }, valueRange = 0f..MAX_BOOST_DB, steps = (MAX_BOOST_DB * 2).toInt() - 1)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0f, 3f, 6f, 9f).forEach { v ->
                // Buttons rather than chips: chip padding leaves too little room for "+9.0 dB" in four columns.
                val label = @Composable { Text(formatBoost(v), maxLines = 1, softWrap = false) }
                if (db == v) FilledTonalButton(onClick = { onBoost(v) }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { label() }
                else OutlinedButton(onClick = { onBoost(v) }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { label() }
            }
        }
    }
}

private fun formatBoost(db: Float): String = String.format(Locale.US, "+%.1f dB", db)

@Composable
private fun SleepSheet(s: EngineState, onMinutes: (Int) -> Unit, onEndOfTrack: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
        Text("Sleep timer", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        listOf(listOf(5, 10, 15, 20), listOf(30, 45, 60, 90)).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m -> OutlinedButton(onClick = { onMinutes(m) }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("$m min") } }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onEndOfTrack, Modifier.fillMaxWidth()) { Text("End of current segment") }
        if (s.sleep != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Turn off timer") }
        }
    }
}

@Composable
private fun CuePanel(cues: List<Cue>, position: Long, onSeek: (Long) -> Unit) {
    val current = cues.indexOfLast { position >= it.startMs }
    val state = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) state.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(cues) { i, c ->
            val active = i == current && position < c.endMs + 1000
            Text(
                c.text,
                style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().clickable { onSeek(c.startMs) }.padding(vertical = 8.dp),
            )
        }
    }
}
