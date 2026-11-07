package com.sasayaki.player.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.sasayaki.player.data.vfs.Vfs
import java.io.File

/**
 * Shares library files (e.g. PDF scripts) with viewer apps, read-only. Files inside archives are
 * streamed through a proxy file descriptor, so nothing is extracted to storage.
 */
class RefProvider : ContentProvider() {
    private val thread by lazy { HandlerThread("ref-provider").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? = fromUri(uri)?.let { mimeOf(it.fileName) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ref = fromUri(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> ref.fileName
                OpenableColumns.SIZE -> if (ref.entry == null) File(ref.path).length() else ref.size
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        val ref = fromUri(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        if (ref.entry == null) return ParcelFileDescriptor.open(File(ref.path), ParcelFileDescriptor.MODE_READ_ONLY)
        val reader = Vfs.openReader(ref)
        val sm = context!!.getSystemService(StorageManager::class.java)
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = reader.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                var done = 0
                while (done < size) {
                    val n = reader.readAt(offset + done, data, done, size - done)
                    if (n <= 0) break
                    done += n
                }
                done
            } catch (e: Exception) {
                throw ErrnoException("onRead", OsConstants.EIO)
            }
            override fun onRelease() = reader.close()
        }, handler)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun authority(context: Context) = "${context.packageName}.files"

        fun uriFor(context: Context, ref: Ref): Uri = Uri.Builder().scheme("content").authority(authority(context))
            .appendPath(ref.fileName)
            .appendQueryParameter("z", ref.path)
            .apply {
                ref.entry?.let {
                    appendQueryParameter("e", it)
                    appendQueryParameter("s", ref.size.toString())
                    appendQueryParameter("o", ref.lho.toString())
                    appendQueryParameter("m", ref.method.toString())
                    appendQueryParameter("c", ref.csize.toString())
                }
            }
            .build()

        private fun fromUri(uri: Uri): Ref? {
            val path = uri.getQueryParameter("z") ?: return null
            val entry = uri.getQueryParameter("e") ?: return Ref(path)
            return Ref(
                path, entry,
                size = uri.getQueryParameter("s")?.toLongOrNull() ?: 0,
                lho = uri.getQueryParameter("o")?.toLongOrNull() ?: -1,
                method = uri.getQueryParameter("m")?.toIntOrNull() ?: 0,
                csize = uri.getQueryParameter("c")?.toLongOrNull() ?: 0,
            )
        }

        fun mimeOf(name: String): String = when (val ext = Formats.ext(name)) {
            "txt", "md" -> "text/plain"
            "pdf" -> "application/pdf"
            "htm", "html" -> "text/html"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
