package com.example.pdfbuilder.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.pdfbuilder.BuildConfig
import com.example.pdfbuilder.data.GithubRelease
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object UpdateManager {
    private const val GITHUB_API_URL = "https://api.github.com/repos/jacktheLad/PDFBooklet/releases/latest"
    private val client = OkHttpClient()
    private val gson = Gson()

    sealed class UpdateState {
        object Idle : UpdateState()
        object Checking : UpdateState()
        data class Available(val release: GithubRelease) : UpdateState()
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
                    .url(GITHUB_API_URL)
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

                    val release = gson.fromJson(body, GithubRelease::class.java)
                    if (isNewVersion(release.tagName)) {
                        onResult(UpdateState.Available(release))
                    } else {
                        onResult(UpdateState.NoUpdate)
                    }
                }
            } catch (e: Exception) {
                onResult(UpdateState.Error(e.message ?: "Unknown error"))
            }
        }.start()
    }

    private fun isNewVersion(tagName: String): Boolean {
        // Remove 'v' prefix if present
        val remoteVersion = tagName.removePrefix("v")
        val localVersion = BuildConfig.VERSION_NAME
        
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

    fun downloadAndInstall(context: Context, release: GithubRelease, onProgress: (Float) -> Unit, onError: (String) -> Unit) {
        // Construct the raw file URL with proxy prefix to bypass China blocking
        // We use the tag name to construct the filename, assuming the file is committed to the releases/ folder in the repo
        // e.g. https://mirror.ghproxy.com/https://raw.githubusercontent.com/jacktheLad/PDFBooklet/main/releases/PDF小册子-v1.0.12.apk
        val tagName = release.tagName // e.g., v1.0.12
        val filename = "PDF小册子-$tagName.apk"
        val rawUrl = "https://raw.githubusercontent.com/jacktheLad/PDFBooklet/main/releases/$filename"
        val proxyUrl = "https://mirror.ghproxy.com/$rawUrl"

        Thread {
            try {
                val request = Request.Builder().url(proxyUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Fallback to direct URL if proxy fails (though proxy is usually more reliable in CN)
                        val fallbackRequest = Request.Builder().url(rawUrl).build()
                        client.newCall(fallbackRequest).execute().use { fallbackResponse ->
                             if (!fallbackResponse.isSuccessful) {
                                onError("Download failed: ${response.code} / ${fallbackResponse.code}")
                                return@use
                             }
                             handleDownloadResponse(context, fallbackResponse, filename, onProgress, onError)
                        }
                        return@use
                    }
                    handleDownloadResponse(context, response, filename, onProgress, onError)
                }
            } catch (e: Exception) {
                onError("Download error: ${e.message}")
            }
        }.start()
    }

    private fun handleDownloadResponse(
        context: Context, 
        response: okhttp3.Response, 
        filename: String,
        onProgress: (Float) -> Unit, 
        onError: (String) -> Unit
    ) {
        val body = response.body
        if (body == null) {
            onError("Empty response body")
            return
        }

        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename)
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(file)
            val totalLength = body.contentLength()
            
            var bytesCopied: Long = 0
            val buffer = ByteArray(8 * 1024)
            var bytes = inputStream.read(buffer)
            
            while (bytes >= 0) {
                outputStream.write(buffer, 0, bytes)
                bytesCopied += bytes
                if (totalLength > 0) {
                    onProgress(bytesCopied.toFloat() / totalLength)
                }
                bytes = inputStream.read(buffer)
            }
            
            outputStream.close()
            inputStream.close()
            
            installApk(context, file)
        } catch (e: Exception) {
            onError("Save/Install error: ${e.message}")
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }
}
