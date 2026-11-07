package com.sasayaki.player.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sasayaki.player.AppGraph
import com.sasayaki.player.data.Cover
import com.sasayaki.player.data.Doc
import com.sasayaki.player.data.Entry
import com.sasayaki.player.data.Formats
import com.sasayaki.player.data.Ref
import com.sasayaki.player.data.RefProvider
import com.sasayaki.player.data.TextFiles
import com.sasayaki.player.data.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Versions sharing a label (e.g. the same take as MP3 and WAV). */
class VersionGroup(val label: String, val versions: List<Version>) {
    fun preferred(preferLossless: Boolean): Version =
        versions.firstOrNull { (it.format in Formats.LOSSLESS) == preferLossless } ?: versions.first()
}

fun Entry.versionGroups(): List<VersionGroup> =
    versions.groupBy { it.label }.map { (label, vs) -> VersionGroup(label, vs) }

fun Entry.preferredVersion(preferLossless: Boolean): Version? = versionGroups().firstOrNull()?.preferred(preferLossless)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(
    graph: AppGraph,
    entry: Entry,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val progressAll by graph.progress.all.collectAsStateWithLifecycle()
    val durations by graph.durations.map.collectAsStateWithLifecycle()
    val engineState by graph.engine.state.collectAsStateWithLifecycle()
    val progress = progressAll[entry.id]
    val groups = remember(entry) { entry.versionGroups() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Selected format per version group, defaulting to the user's lossless preference.
    val selected = remember(entry) { mutableStateMapOf<String, String>() }
    val expanded = remember(entry) { mutableStateMapOf<String, Boolean>() }
    var menu by remember { mutableStateOf(false) }
    var coverPicker by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Ref?>(null) }
    var readme by remember { mutableStateOf<Doc?>(null) }

    LaunchedEffect(entry) {
        // Probe durations so totals and the whole-work bar are available.
        groups.forEach { g -> graph.durations.ensure(g.preferred(settings.preferLossless).tracks) }
        entry.versions.forEach { graph.durations.ensure(it.tracks) }
    }

    fun play(v: Version, index: Int = 0) {
        graph.engine.play(entry, v, index)
        onOpenPlayer()
    }

    val behavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = behavior,
                title = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (entry.images.isNotEmpty()) DropdownMenuItem(
                                text = { Text("Choose cover") },
                                leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                onClick = { menu = false; coverPicker = true },
                            )
                            if (settings.coverOverrides.containsKey(entry.id)) DropdownMenuItem(
                                text = { Text("Reset cover") },
                                onClick = {
                                    menu = false
                                    scope.launch { graph.settings.update { it.copy(coverOverrides = it.coverOverrides - entry.id) } }
                                },
                            )
                            if (progress != null) DropdownMenuItem(
                                text = { Text("Clear listening progress") },
                                leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                                onClick = { menu = false; graph.progress.clear(entry.id) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomPadding + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Header(entry, settings, onCoverClick = { viewing = settings.coverOverrides[entry.id] ?: entry.cover?.takeIf { !entry.coverEmbedded } }) }

            // Resume card
            val pv = progress?.let { p -> entry.versions.firstOrNull { it.id == p.versionId } }
            if (progress != null && pv != null && !progress.finished) item {
                Card(
                    onClick = { graph.engine.resume(entry, null); onOpenPlayer() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Continue listening", style = MaterialTheme.typography.titleSmall)
                            val t = pv.tracks.getOrNull(progress.index)
                            Text(
                                "${pv.fullLabel()} · ${progress.index + 1}/${pv.tracks.size} ${t?.title ?: ""} · ${formatTime(progress.positionMs)}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { progress.fraction }, Modifier.fillMaxWidth(), drawStopIndicator = {})
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledIconButton(onClick = { graph.engine.resume(entry, null); onOpenPlayer() }) {
                            Icon(Icons.Rounded.PlayArrow, "Continue")
                        }
                    }
                }
            }

            item { SectionTitle(if (groups.size > 1) "${groups.size} versions" else "Audio") }

            items(groups, key = { it.label }) { g ->
                val fmt = selected[g.label] ?: g.preferred(settings.preferLossless).format
                val v = g.versions.firstOrNull { it.format == fmt } ?: g.versions.first()
                val isOpen = expanded[g.label] ?: (groups.size == 1)
                val total = v.tracks.map { durations[it.ref.cacheKey] }.let { l -> if (l.all { it != null }) l.sumOf { it!! } else null }
                val playingThis = engineState.entry?.id == entry.id && engineState.version?.id == v.id
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded[g.label] = !isOpen }.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = buildList {
                                add("${v.tracks.size} track" + if (v.tracks.size == 1) "" else "s")
                                total?.let { add(formatTime(it)) }
                                if (v.inArchive) add("in ZIP")
                            }.joinToString(" · ")
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (v.isFilled) Text(
                                "${v.ownCount} own track${if (v.ownCount == 1) "" else "s"}, rest from “${v.baseLabel}”",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Icon(if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        FilledIconButton(onClick = { play(v) }) { Icon(Icons.Rounded.PlayArrow, "Play ${g.label}") }
                    }
                    if (g.versions.size > 1) {
                        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                            g.versions.forEachIndexed { i, gv ->
                                SegmentedButton(
                                    selected = gv.format == v.format,
                                    onClick = { selected[g.label] = gv.format },
                                    shape = SegmentedButtonDefaults.itemShape(i, g.versions.size),
                                ) { Text(formatLabel(gv.format)) }
                            }
                        }
                    } else {
                        FlowRow(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                            FormatBadge(v.format)
                        }
                    }
                    AnimatedVisibility(isOpen) {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            v.tracks.forEachIndexed { i, t ->
                                val current = playingThis && engineState.index == i
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable { play(v, i) },
                                    leadingContent = {
                                        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                                            if (current) Icon(Icons.Rounded.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary)
                                            else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    headlineContent = {
                                        Text(
                                            t.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                            fontWeight = if (current) FontWeight.SemiBold else null,
                                        )
                                    },
                                    supportingContent = if (t.fromBase) {
                                        { Text("from “${v.baseLabel}”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
                                    } else null,
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (t.subtitle != null) {
                                                Icon(Icons.Rounded.ClosedCaption, "Subtitles", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(formatTime(durations[t.ref.cacheKey]), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (entry.images.isNotEmpty()) {
                item { SectionTitle("Illustrations") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(entry.images) { img ->
                            CoverImage(
                                Cover(img), Modifier.size(112.dp).clickable { viewing = img },
                            )
                        }
                    }
                }
            }

            if (entry.docs.isNotEmpty()) {
                item { SectionTitle("Documents") }
                items(entry.docs) { d ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (d.kind == "txt" || d.kind == "md") readme = d else openExternal(context, d.ref)
                        },
                        leadingContent = { Icon(if (d.kind == "pdf") Icons.Rounded.PictureAsPdf else Icons.AutoMirrored.Rounded.Article, null) },
                        headlineContent = { Text(d.name.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = d.name.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    )
                }
            }

            item {
                Text(
                    entry.sources.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }

    viewing?.let { ImageViewer(it, onDismiss = { viewing = null }) }
    readme?.let { ReadmeDialog(it, onDismiss = { readme = null }) }
    if (coverPicker) CoverPickerDialog(entry, onPick = { ref ->
        coverPicker = false
        scope.launch { graph.settings.update { it.copy(coverOverrides = it.coverOverrides + (entry.id to ref)) } }
    }, onDismiss = { coverPicker = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(entry: Entry, settings: com.sasayaki.player.data.Settings, onCoverClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CoverImage(
            entry.coverFor(settings, Cover.SIZE_LARGE),
            Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).clickable(onClick = onCoverClick),
            corner = 20.dp,
        )
        Spacer(Modifier.height(16.dp))
        SelectionContainer {
            Text(entry.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            entry.rj?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            entry.categoryLabel?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            if (entry.hasArchive) AssistChip(onClick = {}, label = { Text("ZIP") }, leadingIcon = { Icon(Icons.Rounded.FolderZip, null, Modifier.size(16.dp)) })
            if (entry.totalSize > 0) AssistChip(onClick = {}, label = { Text(formatSize(entry.totalSize)) })
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FormatBadge(format: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(formatLabel(format), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

private fun openExternal(context: android.content.Context, ref: Ref) {
    val uri = RefProvider.uriFor(context, ref)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, RefProvider.mimeOf(ref.fileName))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, ref.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewer(ref: Ref, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().combinedClickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            coil3.compose.AsyncImage(
                model = Cover(ref, maxSize = Cover.SIZE_LARGE),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReadmeDialog(doc: Doc, onDismiss: () -> Unit) {
    var text by remember(doc) { mutableStateOf<String?>(null) }
    LaunchedEffect(doc) {
        text = withContext(Dispatchers.IO) { runCatching { TextFiles.read(doc.ref) }.getOrElse { "Could not read file: ${it.message}" } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(doc.name.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Text(
                    text ?: "…", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
    )
}

@Composable
private fun CoverPickerDialog(entry: Entry, onPick: (Ref) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose cover") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entry.images.chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { img ->
                            Column(Modifier.weight(1f).clickable { onPick(img) }) {
                                CoverImage(Cover(img), Modifier.fillMaxWidth().aspectRatio(1f))
                                Text(img.fileName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
    )
}
