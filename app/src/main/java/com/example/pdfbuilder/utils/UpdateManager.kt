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
    // 1. First try: ghproxy (Fast in China)
    private const val VERSION_JSON_URL_PROXY = "https://mirror.ghproxy.com/https://raw.githubusercontent.com/jacktheLad/PDFBooklet/main/version.json"
    // 2. Fallback: Raw GitHub (For VPN/Overseas where ghproxy might be blocked or slow)
    private const val VERSION_JSON_URL_RAW = "https://raw.githubusercontent.com/jacktheLad/PDFBooklet/main/version.json"
    
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
            // Strategy: Try Proxy -> If Update Found, return.
            // If Proxy fails or says "No Update" (could be stale), Try Raw.
            
            var proxyResult: UpdateState? = null
            
            // 1. Try Proxy
            try {
                // Add timestamp to bust cache
                val timestamp = System.currentTimeMillis()
                val proxyUrlWithTs = "$VERSION_JSON_URL_PROXY?t=$timestamp"
                proxyResult = fetchUpdateState(proxyUrlWithTs)
                
                if (proxyResult is UpdateState.Available) {
                    onResult(proxyResult)
                    return@Thread
                }
            } catch (e: Exception) {
                // Ignore proxy error
            }

            // 2. Try Raw (Fallback if Proxy failed or was stale)
            try {
                // Add timestamp to bust cache
                val timestamp = System.currentTimeMillis()
                val rawUrlWithTs = "$VERSION_JSON_URL_RAW?t=$timestamp"
                val rawResult = fetchUpdateState(rawUrlWithTs)
                
                if (rawResult is UpdateState.Available) {
                    onResult(rawResult)
                    return@Thread
                }
                
                // If Raw works but no update, then it's truly no update
                if (rawResult is UpdateState.NoUpdate) {
                    onResult(UpdateState.NoUpdate)
                    return@Thread
                }
            } catch (e: Exception) {
                // Raw failed
            }
            
            // 3. Final Decision
            // If we are here, neither source returned "Available".
            // If Proxy said "NoUpdate", fallback to that (assuming Raw failed due to network)
            if (proxyResult is UpdateState.NoUpdate) {
                onResult(UpdateState.NoUpdate)
            } else {
                onResult(UpdateState.Error("Failed to check updates from both sources"))
            }
        }.start()
    }
    
    private fun fetchUpdateState(url: String): UpdateState {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

            val body = response.body?.string() ?: throw Exception("Empty body")
            val appVersion = gson.fromJson(body, AppVersion::class.java)
            
            return if (isNewVersion(appVersion.version)) {
                UpdateState.Available(appVersion)
            } else {
                UpdateState.NoUpdate
            }
        }
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
        val filename = "PDFBooklet-v${version.version}.apk"
        
        // Robust URL resolution: Handle both raw and already-proxied URLs
        val originalUrl = version.downloadUrl
        var rawUrl = originalUrl
        var proxyUrl = originalUrl
        
        if (originalUrl.contains("mirror.ghproxy.com")) {
            // Case A: Remote JSON provided a Proxy URL
            proxyUrl = originalUrl
            // Attempt to strip proxy to get raw URL
            rawUrl = originalUrl.replace("https://mirror.ghproxy.com/", "")
        } else {
            // Case B: Remote JSON provided a Raw URL
            // rawUrl is already set to originalUrl
            if (rawUrl.startsWith("https://raw.githubusercontent.com") || 
               (rawUrl.startsWith("https://github.com") && rawUrl.contains("/releases/download/"))) {
                proxyUrl = "https://mirror.ghproxy.com/$rawUrl"
            }
        }

        Thread {
            var success = false
            
            // 1. Try Proxy First (Preferred for China)
            if (proxyUrl != rawUrl) {
                try {
                    if (performDownload(context, proxyUrl, filename, onProgress)) {
                        success = true
                    }
                } catch (e: Exception) {
                    // Ignore proxy error (e.g. DNS failure on VPN), continue to fallback
                }
            }

            // 2. Try Raw Fallback (For VPN/Overseas or if Proxy fails)
            if (!success) {
                try {
                    if (performDownload(context, rawUrl, filename, onProgress)) {
                        // Success
                    } else {
                        onError("Download failed from both sources")
                    }
                } catch (e: Exception) {
                    onError("Download error: ${e.message}")
                }
            }
        }.start()
    }
    
    private fun performDownload(context: Context, url: String, filename: String, onProgress: (Float) -> Unit): Boolean {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            
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
            return true
        }
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
