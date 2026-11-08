package com.sasayaki.player.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import com.sasayaki.player.ui.theme.LocalBaseColorScheme
import com.sasayaki.player.ui.theme.isDark
import com.sasayaki.player.ui.theme.withAccent
import com.sasayaki.player.ui.theme.withSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sasayaki.player.AppGraph
import com.sasayaki.player.data.Settings
import com.sasayaki.player.data.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, bottomPadding: androidx.compose.ui.unit.Dp, onBack: () -> Unit, onAddFolder: () -> Unit) {
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val library by graph.library.library.collectAsStateWithLifecycle()
    val scan by graph.library.scan.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun update(block: (Settings) -> Settings) = scope.launch { graph.settings.update(block) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomPadding + 24.dp)) {
            item { SectionTitle("Library folders") }
            items(settings.roots) { r ->
                val count = library.entries.count { it.id.startsWith("$r::") }
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    headlineContent = { Text(r) },
                    supportingContent = { Text("$count works") },
                    trailingContent = {
                        IconButton(onClick = {
                            scope.launch {
                                graph.settings.update { it.copy(roots = it.roots - r) }
                                graph.library.rescan()
                            }
                        }) { Icon(Icons.Rounded.Delete, "Remove") }
                    },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = onAddFolder),
                    leadingContent = { Icon(Icons.Rounded.Add, null) },
                    headlineContent = { Text("Add folder") },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(enabled = scan == null) { graph.library.rescan() },
                    leadingContent = { Icon(Icons.Rounded.Refresh, null) },
                    headlineContent = { Text(if (scan != null) "Scanning…" else "Rescan library") },
                    supportingContent = { Text("${library.entries.size} works") },
                )
            }

            item { SectionTitle("Appearance") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        val modes = listOf(ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light", ThemeMode.SYSTEM to "System")
                        modes.forEachIndexed { i, (m, label) ->
                            SegmentedButton(
                                selected = settings.theme == m, onClick = { update { it.copy(theme = m) } },
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            ) { Text(label) }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) item {
                SwitchRow("Dynamic color", "Use colors from your wallpaper", settings.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }
            }
            item {
                ColorsRow(settings.accentColor, settings.secondaryColor, isDark(settings.theme)) { a, s ->
                    update { it.copy(accentColor = a, secondaryColor = s) }
                }
            }
            item {
                SwitchRow("Colors from covers", null, settings.coverTint) { v -> update { it.copy(coverTint = v) } }
            }
            item {
                SwitchRow("Whole-work progress", null, settings.workBar) { v -> update { it.copy(workBar = v) } }
            }

            item { SectionTitle("Playback") }
            item {
                SwitchRow("Prefer lossless", "Pick WAV/FLAC over MP3 when a work has both", settings.preferLossless) { v -> update { it.copy(preferLossless = v) } }
            }
            item {
                ChoiceRow("Skip back", listOf(5, 10, 15, 30), settings.skipBackSec, { "${it}s" }) { v -> update { it.copy(skipBackSec = v) } }
            }
            item {
                ChoiceRow("Skip forward", listOf(10, 15, 30, 60), settings.skipForwardSec, { "${it}s" }) { v -> update { it.copy(skipForwardSec = v) } }
            }
            item {
                ChoiceRow("Rewind on resume", listOf(0, 3, 5, 10), settings.resumeRewindSec, { if (it == 0) "Off" else "${it}s" }) { v ->
                    update { it.copy(resumeRewindSec = v) }
                }
            }

            item { SectionTitle("Versions") }
            item {
                SwitchRow(
                    "Fill partial versions from the main version",
                    null,
                    settings.fillPartial,
                ) { v ->
                    scope.launch {
                        graph.settings.update { it.copy(fillPartial = v) }
                        graph.library.rescan()
                    }
                }
            }

            item { SectionTitle("Storage") }
            item {
                ListItem(
                    modifier = Modifier.clickable { scope.launch(kotlinx.coroutines.Dispatchers.IO) { graph.covers.clear() } },
                    headlineContent = { Text("Clear thumbnail cache") },
                )
            }
        }
    }
}

@Composable
private fun ColorsRow(accent: Int?, secondary: Int?, dark: Boolean, onApply: (Int?, Int?) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    // The theme may carry cover colors; the swatches show the picked ones.
    val picked = LocalBaseColorScheme.current.withAccent(accent, dark).withSecondary(secondary, dark)
    ListItem(
        modifier = Modifier.clickable { picking = true },
        headlineContent = { Text("Colors") },
        supportingContent = { Text(if (accent == null && secondary == null) "Default" else "Custom") },
        trailingContent = {
            // The secondary swatch tucks behind the accent one, ringed in the background color to separate them.
            Box(Modifier.size(width = 52.dp, height = 36.dp)) {
                Box(Modifier.align(Alignment.CenterEnd).size(32.dp).clip(CircleShape).background(picked.secondary))
                Box(
                    Modifier.align(Alignment.CenterStart).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp).clip(CircleShape).background(picked.primary),
                )
            }
        },
    )
    if (picking) {
        ColorsDialog(
            accent = accent,
            secondary = secondary,
            dark = dark,
            onApply = { a, s -> picking = false; onApply(a, s) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, value: T, label: (T) -> String, onChange: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(selected = o == value, onClick = { onChange(o) }, label = { Text(label(o)) }) }
        }
    }
}
