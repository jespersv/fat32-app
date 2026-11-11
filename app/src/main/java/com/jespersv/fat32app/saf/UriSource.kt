package com.jespersv.fat32app.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.jespersv.fat32app.core.Copier
import java.io.FileNotFoundException
import java.io.InputStream

/** A file the user picked or shared, read through its content URI. */
class UriSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val name: String,
    override val size: Long,
) : Copier.Source {

    override fun open(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException("Cannot open $name")

    companion object {
        fun from(resolver: ContentResolver, uri: Uri): UriSource {
            var name: String? = null
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val nameAt = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeAt = c.getColumnIndex(OpenableColumns.SIZE)
                            if (nameAt >= 0 && !c.isNull(nameAt)) name = c.getString(nameAt)
                            if (sizeAt >= 0 && !c.isNull(sizeAt)) size = c.getLong(sizeAt)
                        }
                    }
            }
            return UriSource(resolver, uri, name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file", size)
        }
    }
}
