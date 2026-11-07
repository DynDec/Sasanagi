package com.sasayaki.player.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sasayaki.player.data.Formats
import com.sasayaki.player.data.scan.Naming
import com.sasayaki.player.data.vfs.Vfs
import java.io.File

private class Volume(val label: String, val dir: File, val removable: Boolean)

private fun volumes(context: Context): List<Volume> {
    val sm = context.getSystemService(StorageManager::class.java)
    val out = ArrayList<Volume>()
    if (Build.VERSION.SDK_INT >= 30) {
        for (v in sm.storageVolumes) {
            val dir = v.directory ?: continue
            out += Volume(v.getDescription(context), dir, v.isRemovable)
        }
    } else {
        out += Volume("Internal storage", Environment.getExternalStorageDirectory(), false)
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }
            ?.forEach { out += Volume(it.name, it, true) }
    }
    return out
}

/** Browses storage directly (the app has all-files access) to choose a library folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val vols = remember { volumes(context) }
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    val dir = path?.let(::File)
    val children = remember(path) {
        dir?.listFiles()?.filter { !Vfs.isJunk(it.name) && (it.isDirectory || Formats.isArchive(it.name)) }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenComparator { a, b -> Naming.compareNatural(a.name, b.name) })
            ?: emptyList()
    }
    val root = vols.firstOrNull { dir != null && dir.absolutePath == it.dir.absolutePath }

    fun up() {
        path = if (dir == null || root != null) null else dir.parentFile?.absolutePath
    }
    BackHandler { if (path == null) onDismiss() else up() }

    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Choose library folder")
                            Text(dir?.absolutePath ?: "Storage", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { if (path == null) onDismiss() else up() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                )
            },
            bottomBar = {
                if (dir != null) Surface(tonalElevation = 3.dp) {
                    Button(onClick = { onPick(dir) }, modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp)) { Text("Use this folder") }
                }
            },
        ) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                if (dir == null) {
                    items(vols) { v ->
                        ListItem(
                            modifier = Modifier.clickable { path = v.dir.absolutePath },
                            leadingContent = { Icon(if (v.removable) Icons.Rounded.SdCard else Icons.Rounded.PhoneAndroid, null) },
                            headlineContent = { Text(v.label) },
                            supportingContent = { Text(v.dir.absolutePath) },
                        )
                    }
                } else {
                    items(children, key = { it.absolutePath }) { f ->
                        ListItem(
                            modifier = if (f.isDirectory) Modifier.clickable { path = f.absolutePath } else Modifier,
                            leadingContent = {
                                Icon(if (f.isDirectory) Icons.Rounded.Folder else Icons.Rounded.FolderZip, null,
                                    tint = if (f.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            headlineContent = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                    if (children.isEmpty()) item {
                        Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
}
