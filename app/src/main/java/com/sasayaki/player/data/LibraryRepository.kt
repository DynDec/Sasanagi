package com.sasayaki.player.data

import android.content.Context
import com.sasayaki.player.data.scan.LibraryScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ScanState(val done: Int, val total: Int, val current: String)

class LibraryRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val store = JsonStore(File(context.filesDir, "library.json"), Library.serializer())

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _scan = MutableStateFlow<ScanState?>(null)
    val scan: StateFlow<ScanState?> = _scan.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    @Volatile private var index: Map<String, Entry> = emptyMap()
    private var scanJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            store.load()?.let { publish(it) }
            _loaded.value = true
        }
    }

    fun entry(id: String): Entry? = index[id]

    private fun publish(lib: Library) {
        index = lib.entries.associateBy { it.id }
        _library.value = lib
    }

    /** Rescans every library root. Existing data stays visible while scanning. */
    fun rescan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch(Dispatchers.IO) {
            val s = settings.current
            _scan.value = ScanState(0, 0, "")
            try {
                val roots = s.roots.map(::File)
                val entries = LibraryScanner(s.fillPartial).scan(roots) { done, total, current ->
                    _scan.value = ScanState(done, total, current)
                }
                val lib = Library(roots = s.roots, entries = entries, scannedAt = System.currentTimeMillis())
                publish(lib)
                runCatching { store.save(lib) }
            } finally {
                _scan.value = null
            }
        }
    }
}
