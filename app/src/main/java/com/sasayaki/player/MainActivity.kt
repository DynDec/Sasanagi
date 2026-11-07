package com.sasayaki.player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sasayaki.player.playback.PlaybackService
import com.sasayaki.player.ui.EntryScreen
import com.sasayaki.player.ui.FolderPicker
import com.sasayaki.player.ui.LibraryScreen
import com.sasayaki.player.ui.MiniPlayer
import com.sasayaki.player.ui.PlayerScreen
import com.sasayaki.player.ui.SettingsScreen
import com.sasayaki.player.ui.theme.SasayakiTheme
import com.sasayaki.player.ui.theme.isDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null
    private val openPlayer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as SasayakiApp).graph
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayer.value = true
        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            val dark = isDark(settings.theme)
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                )
            }
            SasayakiTheme(settings.theme, settings.dynamicColor, settings.secondaryColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var granted by remember { mutableStateOf(hasStorageAccess()) }
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasStorageAccess() }
                    if (granted) App(graph, openPlayer) else PermissionScreen { granted = hasStorageAccess() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer.value = true
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller keeps the session service alive while the UI drives the player.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        (application as SasayakiApp).graph.engine.saveProgress()
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun PermissionScreen(onResult: () -> Unit) {
        val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("Storage access", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sasayaki plays your works straight from their folders and ZIP archives, without copying them. " +
                    "To read them in place it needs access to all files.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                    runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                } else {
                    legacy.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }) { Text("Grant access") }
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

private sealed interface Route {
    data object Library : Route
    data class Entry(val id: String) : Route
    data object Settings : Route
}

@Composable
private fun App(graph: AppGraph, openPlayer: androidx.compose.runtime.MutableState<Boolean>) {
    val stack = remember { mutableStateListOf<Route>(Route.Library) }
    var picker by rememberSaveable { mutableStateOf(false) }
    val engineState by graph.engine.state.collectAsStateWithLifecycle()
    val library by graph.library.library.collectAsStateWithLifecycle()
    val loaded by graph.library.loaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        // Pick up new downloads on every launch; the previous index stays visible meanwhile.
        if (graph.settings.current.roots.isNotEmpty()) graph.library.rescan()
        graph.engine.restoreLastSession()
    }

    val hasMedia = engineState.entry != null
    val miniHeight = if (hasMedia) 73.dp else 0.dp
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = miniHeight + navBottom
    val top = stack.last()

    BackHandler(enabled = openPlayer.value) { openPlayer.value = false }
    BackHandler(enabled = !openPlayer.value && !picker && stack.size > 1) { stack.removeAt(stack.lastIndex) }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { route ->
            when (route) {
                Route.Library -> LibraryScreen(
                    graph, bottomPadding,
                    onOpen = { stack.add(Route.Entry(it.id)) },
                    onSettings = { stack.add(Route.Settings) },
                    onAddFolder = { picker = true },
                )
                is Route.Entry -> {
                    val entry = remember(library, route.id) { graph.library.entry(route.id) }
                    if (entry == null) LaunchedEffect(Unit) { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
                    else EntryScreen(graph, entry, bottomPadding, onBack = { stack.removeAt(stack.lastIndex) }, onOpenPlayer = { openPlayer.value = true })
                }
                Route.Settings -> SettingsScreen(graph, bottomPadding, onBack = { stack.removeAt(stack.lastIndex) }, onAddFolder = { picker = true })
            }
        }

        AnimatedVisibility(
            visible = hasMedia && !openPlayer.value,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MiniPlayer(graph, onExpand = { openPlayer.value = true })
        }

        AnimatedVisibility(
            visible = openPlayer.value && hasMedia,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            PlayerScreen(
                graph,
                onCollapse = { openPlayer.value = false },
                onOpenEntry = {
                    val id = engineState.entry?.id
                    openPlayer.value = false
                    if (id != null && (top as? Route.Entry)?.id != id) stack.add(Route.Entry(id))
                },
            )
        }

        if (picker) {
            FolderPicker(
                onPick = { dir ->
                    picker = false
                    scope.launch {
                        graph.settings.update { s -> s.copy(roots = (s.roots + dir.absolutePath).distinct()) }
                        graph.library.rescan()
                    }
                },
                onDismiss = { picker = false },
            )
        }
    }
}
