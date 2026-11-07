package com.sasayaki.player.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Atomically persisted JSON file in the app's private storage (metadata only, never media). */
class JsonStore<T>(private val file: File, private val serializer: KSerializer<T>) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun load(): T? = runCatching {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else null
    }.getOrNull()

    @Synchronized
    fun save(value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
