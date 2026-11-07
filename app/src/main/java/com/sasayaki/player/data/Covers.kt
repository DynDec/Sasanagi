package com.sasayaki.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.sasayaki.player.data.vfs.Vfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.security.MessageDigest

/** Image request for an entry illustration (or a track's embedded artwork). */
data class Cover(val ref: Ref, val embedded: Boolean = false, val maxSize: Int = SIZE_THUMB) {
    companion object {
        const val SIZE_THUMB = 320
        const val SIZE_LARGE = 1440
    }
}

/**
 * Downscaled JPEG copies of illustrations kept in the app cache, so the list does not decode
 * 10 MB PNGs (or read from archives) every time. Audio is never copied.
 */
class CoverCache(context: Context) {
    private val dir = File(context.cacheDir, "covers").apply { mkdirs() }
    private val locks = Array(8) { Mutex() }

    fun fileFor(c: Cover): File {
        val md = MessageDigest.getInstance("SHA-1").digest("${c.ref.cacheKey}|${c.embedded}|${c.maxSize}".toByteArray())
        return File(dir, md.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    /** Returns the cached file, generating it if needed; null when the image can't be decoded. */
    suspend fun get(c: Cover): File? = withContext(Dispatchers.IO) {
        val f = fileFor(c)
        if (f.exists() && f.length() > 0) return@withContext f
        locks[(f.name.hashCode() and 0x7fffffff) % locks.size].withLock {
            if (f.exists() && f.length() > 0) return@withLock f
            val bmp = decode(c) ?: return@withLock null
            val tmp = File(dir, f.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            tmp.renameTo(f)
            f
        }
    }

    private fun decode(c: Cover): Bitmap? = runCatching {
        val bytes: ByteArray? = if (c.embedded) MediaProbe.embeddedPicture(c.ref) else null
        if (c.embedded && bytes == null) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        else Vfs.openStream(c.ref).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= c.maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = (if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        else Vfs.openStream(c.ref).use { BitmapFactory.decodeStream(it.buffered(256 * 1024), null, opts) }) ?: return null
        val longest = maxOf(raw.width, raw.height)
        if (longest <= c.maxSize) raw else {
            val scale = c.maxSize.toFloat() / longest
            Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== raw) raw.recycle() }
        }
    }.getOrNull()

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

class CoverFetcher(private val cover: Cover, private val cache: CoverCache) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val file = cache.get(cover) ?: return null
        return SourceFetchResult(
            source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val cache: CoverCache) : Fetcher.Factory<Cover> {
        override fun create(data: Cover, options: Options, imageLoader: ImageLoader): Fetcher = CoverFetcher(data, cache)
    }
}

class CoverKeyer : Keyer<Cover> {
    override fun key(data: Cover, options: Options): String = "cover:${data.ref.cacheKey}:${data.embedded}:${data.maxSize}"
}
