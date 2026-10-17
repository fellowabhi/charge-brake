package com.fellowabhi.chargebrake

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Minimal read-only provider so the CSV log can be shared (no AndroidX FileProvider needed). */
class LogProvider : ContentProvider() {
    private fun file() = File(context!!.cacheDir, NAME)
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/csv"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(NAME, file().length())) }
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val NAME = "charge-brake-log.csv"
        val URI: Uri = Uri.parse("content://com.fellowabhi.chargebrake.log/$NAME")
    }
}
