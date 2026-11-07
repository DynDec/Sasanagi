package com.sasayaki.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.sasayaki.player.data.Ref
import com.sasayaki.player.data.vfs.Vfs
import com.sasayaki.player.data.vfs.ZipEntryReader
import java.io.File

/** Maps [Ref]s to URIs ExoPlayer can open: plain files as file://, archive entries as szip://. */
object RefUris {
    const val SCHEME = "szip"

    fun toUri(ref: Ref): Uri = if (ref.entry == null) {
        Uri.fromFile(File(ref.path))
    } else {
        Uri.Builder().scheme(SCHEME).authority("a")
            // The entry name as path keeps the file extension visible for format sniffing.
            .appendPath(ref.fileName)
            .appendQueryParameter("z", ref.path)
            .appendQueryParameter("e", ref.entry)
            .appendQueryParameter("s", ref.size.toString())
            .appendQueryParameter("o", ref.lho.toString())
            .appendQueryParameter("m", ref.method.toString())
            .appendQueryParameter("c", ref.csize.toString())
            .build()
    }

    fun fromUri(uri: Uri): Ref? = when (uri.scheme) {
        SCHEME -> Ref(
            path = uri.getQueryParameter("z") ?: return null,
            entry = uri.getQueryParameter("e") ?: return null,
            size = uri.getQueryParameter("s")?.toLongOrNull() ?: 0,
            lho = uri.getQueryParameter("o")?.toLongOrNull() ?: -1,
            method = uri.getQueryParameter("m")?.toIntOrNull() ?: 0,
            csize = uri.getQueryParameter("c")?.toLongOrNull() ?: 0,
        )
        "file" -> uri.path?.let { Ref(it, size = File(it).length()) }
        else -> null
    }
}

/** Streams a file stored inside a ZIP archive directly, without extracting it anywhere. */
@UnstableApi
class ArchiveDataSource : BaseDataSource(false) {
    private var reader: ZipEntryReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val ref = RefUris.fromUri(dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val r = try {
            Vfs.openReader(ref)
        } catch (e: java.io.FileNotFoundException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        reader = r
        if (dataSpec.position > r.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = try {
            reader!!.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            reader?.close()
        } finally {
            reader = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** Routes szip:// URIs to [ArchiveDataSource] and everything else to the default implementation. */
@UnstableApi
class RoutingDataSource(private val fallback: DataSource) : DataSource {
    private val archive = ArchiveDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        archive.addTransferListener(transferListener)
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == RefUris.SCHEME) archive else fallback
        current = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
    override fun getUri(): Uri? = current?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
    override fun close() {
        try { current?.close() } finally { current = null }
    }

    class Factory(private val context: android.content.Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(DefaultDataSource(context, false))
    }
}
