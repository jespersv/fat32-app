package com.jespersv.fat32app.saf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.jespersv.fat32app.core.Entry
import com.jespersv.fat32app.core.NewFile
import com.jespersv.fat32app.core.Volume
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * A folder on a drive that Android itself has mounted, reached through the
 * Storage Access Framework. Android owns the file system here, so the app
 * cannot unmount it: the user has to eject the drive in Android before
 * unplugging.
 */
class SafVolume(context: Context, private val tree: Uri) : Volume {

    private val resolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    override val title: String = runCatching { displayName(rootId) }.getOrNull() ?: "Selected folder"
    override val fsName: String = "mounted by Android"

    /** Unknown here; if the drive is FAT, Android reports the 4 GiB limit as a write error. */
    override val maxFileSize: Long = Long.MAX_VALUE

    override fun freeBytes(): Long? = null

    override fun list(dir: List<String>): List<Entry> = wrap {
        children(resolve(dir)).map { Entry(it.name, it.isDir, it.size) }
    }

    override fun mkdir(dir: List<String>, name: String) {
        wrap {
            DocumentsContract.createDocument(resolver, uriOf(resolve(dir)), Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Android could not create the folder")
        }
    }

    override fun create(dir: List<String>, name: String, size: Long): NewFile = wrap {
        val uri = DocumentsContract.createDocument(resolver, uriOf(resolve(dir)), "application/octet-stream", name)
            ?: throw IOException("Android could not create the file")
        Writing(uri)
    }

    override fun delete(dir: List<String>, name: String) {
        wrap {
            val doc = children(resolve(dir)).firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return@wrap
            DocumentsContract.deleteDocument(resolver, uriOf(doc.id))
        }
    }

    override fun flush() {}

    override fun close() {}

    private class Doc(val id: String, val name: String, val isDir: Boolean, val size: Long)

    private fun uriOf(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    private fun displayName(documentId: String): String? =
        resolver.query(uriOf(documentId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private fun children(parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
        )
        val cursor = resolver.query(uri, columns, null, null, null)
            ?: throw IOException("The folder is no longer available")
        val out = ArrayList<Doc>()
        cursor.use { c ->
            while (c.moveToNext()) {
                out += Doc(
                    c.getString(0),
                    if (c.isNull(1)) "" else c.getString(1),
                    !c.isNull(2) && c.getString(2) == Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) 0L else c.getLong(3),
                )
            }
        }
        return out
    }

    private fun resolve(path: List<String>): String {
        var id = rootId
        for (segment in path) {
            id = children(id).firstOrNull { it.isDir && it.name.equals(segment, ignoreCase = true) }?.id
                ?: throw FileNotFoundException("Folder not found: $segment")
        }
        return id
    }

    /** Providers report a vanished drive in several ways; the rest of the app expects IOException. */
    private inline fun <T> wrap(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "The folder is no longer available", e)
        }

    private inner class Writing(private var uri: Uri) : NewFile {
        private val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("Android could not open the new file for writing")
        private val out = FileOutputStream(descriptor.fileDescriptor)
        private var closed = false

        override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)

        override fun finish() {
            out.flush()
            // Ask the kernel to push the data to the drive now. Not every provider supports
            // this, and the user still has to eject in Android, so a refusal is not an error.
            runCatching { descriptor.fileDescriptor.sync() }
            closeStreams()
        }

        override fun openRead(): InputStream = wrap {
            resolver.openInputStream(uri) ?: throw IOException("Cannot read the file back")
        }

        override fun renameTo(name: String) {
            wrap {
                // The provider returns a new URI when the document id changes with the name.
                DocumentsContract.renameDocument(resolver, uri, name)?.let { uri = it }
            }
        }

        override fun discard() {
            runCatching { closeStreams() }
            wrap { DocumentsContract.deleteDocument(resolver, uri) }
        }

        private fun closeStreams() {
            if (closed) return
            closed = true
            out.close()
            descriptor.close()
        }
    }
}
