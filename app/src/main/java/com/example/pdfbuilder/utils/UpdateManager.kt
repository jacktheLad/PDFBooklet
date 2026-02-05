package com.example.pdfbuilder.utils

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.pdfbuilder.BuildConfig
import com.example.pdfbuilder.data.AppVersion
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object UpdateManager {
    // Use ghproxy to access version.json on main branch for fast, reliable checks in China
    private const val VERSION_JSON_URL = "https://mirror.ghproxy.com/https://raw.githubusercontent.com/jacktheLad/PDFBooklet/main/version.json"
    private val client = OkHttpClient()
    private val gson = Gson()

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class Available(val version: AppVersion) : UpdateState()
        object NoUpdate : UpdateState()
        data class Error(val message: String) : UpdateState()
        data class Downloading(val progress: Float) : UpdateState()
        object Downloaded : UpdateState()
    }

    fun checkForUpdate(onResult: (UpdateState) -> Unit) {
        onResult(UpdateState.Checking)
        
        Thread {
            try {
                val request = Request.Builder()
                    .url(VERSION_JSON_URL)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onResult(UpdateState.Error("Failed to check updates: ${response.code}"))
                        return@use
                    }

                    val body = response.body?.string()
                    if (body == null) {
                        onResult(UpdateState.Error("Empty response"))
                        return@use
                    }

                    val appVersion = gson.fromJson(body, AppVersion::class.java)
                    if (isNewVersion(appVersion.version)) {
                        onResult(UpdateState.Available(appVersion))
                    } else {
                        onResult(UpdateState.NoUpdate)
                    }
                }
            } catch (e: Exception) {
                onResult(UpdateState.Error(e.message ?: "Unknown error"))
            }
        }.start()
    }

    private fun isNewVersion(remoteVersionStr: String): Boolean {
        // Remove 'v' prefix if present
        val remoteVersion = remoteVersionStr.removePrefix("v")
        val localVersion = BuildConfig.VERSION_NAME.removePrefix("v")
        
        // Simple semantic versioning check
        val remoteParts = remoteVersion.split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = localVersion.split(".").map { it.toIntOrNull() ?: 0 }
        
        val length = maxOf(remoteParts.size, localParts.size)
        
        for (i in 0 until length) {
            val remote = remoteParts.getOrElse(i) { 0 }
            val local = localParts.getOrElse(i) { 0 }
            
            if (remote > local) return true
            if (remote < local) return false
        }
        
        return false
    }

    fun downloadAndInstall(context: Context, version: AppVersion, onProgress: (Float) -> Unit, onError: (String) -> Unit) {
        // The downloadUrl in version.json should be the full URL.
        // We ensure it goes through proxy if needed for China access.
        
        var downloadUrl = version.downloadUrl
        if (downloadUrl.startsWith("https://raw.githubusercontent.com")) {
            downloadUrl = "https://mirror.ghproxy.com/$downloadUrl"
        } else if (downloadUrl.startsWith("https://github.com") && downloadUrl.contains("/releases/download/")) {
             // GitHub releases download link is also slow in China, proxy it
             downloadUrl = "https://mirror.ghproxy.com/$downloadUrl"
        }

        val filename = "PDFBooklet-v${version.version}.apk"

        Thread {
            try {
                val request = Request.Builder().url(downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onError("Download failed: ${response.code}")
                        return@use
                    }

                    val body = response.body
                    if (body == null) {
                        onError("Empty response body")
                        return@use
                    }

                    val total = body.contentLength()
                    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename)
                    
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(file)
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead: Long = 0
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (total > 0) {
                            onProgress(totalRead.toFloat() / total.toFloat())
                        }
                    }
                    
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    
                    onProgress(1.0f)
                    installApk(context, file)
                }
            } catch (e: Exception) {
                onError(e.message ?: "Download error")
            }
        }.start()
    }

    private fun installApk(context: Context, file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
