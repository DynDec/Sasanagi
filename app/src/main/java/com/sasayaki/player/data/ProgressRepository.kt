package com.sasayaki.player.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Per-entry listening position, so every work resumes where it was left. */
@OptIn(FlowPreview::class)
class ProgressRepository(context: Context, scope: CoroutineScope) {
    private val store = JsonStore(
        File(context.filesDir, "progress.json"),
        MapSerializer(String.serializer(), Progress.serializer()),
    )
    private val _all = MutableStateFlow(store.load() ?: emptyMap())
    val all: StateFlow<Map<String, Progress>> = _all.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            _all.drop(1).debounce(1500).collect { runCatching { store.save(it) } }
        }
    }

    operator fun get(entryId: String): Progress? = _all.value[entryId]

    fun put(p: Progress) {
        _all.value = _all.value + (p.entryId to p)
    }

    fun clear(entryId: String) {
        _all.value = _all.value - entryId
    }

    fun flush() = runCatching { store.save(_all.value) }

    val last: Progress? get() = _all.value.values.maxByOrNull { it.updatedAt }
}
