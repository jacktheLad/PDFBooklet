package com.example.pdfbuilder.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

object SafUtils {

    /**
     * 尝试通过 DocumentFile 或 ContentResolver 查询出给定 Uri 对应的显示名称。
     */
    fun getDisplayName(context: Context, uri: Uri): String? {
        val documentFile = DocumentFile.fromSingleUri(context, uri)
        if (documentFile != null) {
            val name = documentFile.name
            if (!name.isNullOrEmpty()) {
                return name
            }
        }

        val resolver = context.contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }

        return null
    }

    /**
     * 返回给定 Uri 对应内容的大致字节大小，如果无法获取则返回 null。
     */
    fun getSizeBytes(context: Context, uri: Uri): Long? {
        val documentFile = DocumentFile.fromSingleUri(context, uri)
        if (documentFile != null) {
            val length = documentFile.length()
            if (length > 0L) {
                return length
            }
        }

        val resolver = context.contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index != -1 && cursor.moveToFirst()) {
                return cursor.getLong(index)
            }
        }

        return null
    }
}
