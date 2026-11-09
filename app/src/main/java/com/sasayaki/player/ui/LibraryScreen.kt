package com.sasayaki.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sasayaki.player.AppGraph
import com.sasayaki.player.data.Entry
import com.sasayaki.player.data.Progress
import com.sasayaki.player.data.SortMode
import com.sasayaki.player.data.scan.Naming
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    graph: AppGraph,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpen: (Entry) -> Unit,
    onSettings: () -> Unit,
    onAddFolder: () -> Unit,
) {
    val library by graph.library.library.collectAsStateWithLifecycle()
    val loaded by graph.library.loaded.collectAsStateWithLifecycle()
    val scan by graph.library.scan.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val progress by graph.progress.all.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }

    val categories = remember(library) { library.entries.mapNotNull { it.categoryLabel }.distinct().sortedWith(Naming::compareNatural) }
    val hasUncategorized = remember(library) { library.entries.any { it.categoryLabel == null } }
    val shown = remember(library, query, category, settings.sort, progress) {
        val q = Naming.norm(query)
        library.entries
            .filter { category == null || (it.categoryLabel ?: "") == category }
            .filter {
                q.isEmpty() || Naming.norm(it.title).contains(q) || it.rj?.lowercase()?.contains(q) == true ||
                    it.categoryLabel?.let { c -> Naming.norm(c).contains(q) } == true
            }
            .let { list ->
                when (settings.sort) {
                    SortMode.TITLE -> list.sortedWith { a, b -> Naming.compareNatural(a.title, b.title) }
                    SortMode.RECENTLY_ADDED -> list.sortedByDescending { it.modified }
                    SortMode.RECENTLY_PLAYED -> list.sortedWith(
                        compareByDescending<Entry> { progress[it.id]?.updatedAt ?: 0L }.thenComparator { a, b -> Naming.compareNatural(a.title, b.title) }
                    )
                }
            }
    }

    val behavior = TopAppBarDefaults.pinnedScrollBehavior()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = behavior,
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search")
                    }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search title, RJ code…") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    } else {
                        Text("Sasanagi")
                    }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(SortMode.TITLE to "Title", SortMode.RECENTLY_ADDED to "Recently added", SortMode.RECENTLY_PLAYED to "Recently played")
                                .forEach { (mode, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = { RadioButton(selected = settings.sort == mode, onClick = null) },
                                        onClick = {
                                            sortMenu = false
                                            scope.launch { graph.settings.update { it.copy(sort = mode) } }
                                        },
                                    )
                                }
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            if (categories.size + (if (hasUncategorized) 1 else 0) > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") }) }
                    if (hasUncategorized) item {
                        FilterChip(selected = category == "", onClick = { category = if (category == "") null else "" }, label = { Text("Uncategorized") })
                    }
                    items(categories) { c ->
                        FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c) })
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { graph.library.rescan() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    !loaded -> Unit
                    settings.roots.isEmpty() -> EmptyState(
                        "No library folders yet", null,
                        "Add folder", onAddFolder,
                    )
                    library.entries.isEmpty() && scan == null -> EmptyState(
                        "Nothing found", null,
                        "Rescan", { graph.library.rescan() },
                    )
                    else -> {
                        val listState = rememberLazyListState()
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding + 16.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(shown, key = { it.id }) { e ->
                                EntryRow(e, graph, settings, progress[e.id], onClick = { onOpen(e) })
                            }
                            if (shown.isEmpty()) item {
                                Text(
                                    "No matches", textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: Entry, graph: AppGraph, settings: com.sasayaki.player.data.Settings, p: Progress?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(e.coverFor(settings), Modifier.size(72.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val versions = e.versions.map { it.label }.distinct().size
            val meta = buildList {
                e.categoryLabel?.let { add(it) }
                add("${e.trackCount} track" + if (e.trackCount == 1) "" else "s")
                if (versions > 1) add("$versions versions")
                add(e.formats.joinToString("/") { formatLabel(it) })
            }.joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.hasArchive && !e.hasFolder) {
                    Icon(Icons.Rounded.FolderZip, "ZIP", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (p != null) {
                Spacer(Modifier.height(6.dp))
                if (p.finished) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Finished", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Headphones, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        LinearProgressIndicator(
                            progress = { p.fraction.coerceAtLeast(0.02f) },
                            modifier = Modifier.weight(1f),
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String?, action: String?, onAction: () -> Unit) {
    // Scrollable so pull-to-refresh also works on the empty state.
    LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        item {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (body != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                if (action != null) {
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}
