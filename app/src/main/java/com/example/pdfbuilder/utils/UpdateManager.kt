package com.example.pdfbuilder.utils

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.pdfbuilder.BuildConfig
import com.example.pdfbuilder.data.AppVersion
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object UpdateManager {
    // Base URL for version.json (Direct)
    private const val VERSION_URL_DIRECT = "https://raw.githubusercontent.com/jacktheLad/PDFBooklet/main/version.json"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        
        scope.launch {
            try {
                // Construct race candidates
                val timestamp = System.currentTimeMillis()
                val urls = listOf(
                    // 1. ghproxy.net (Champion - Fast & Stable)
                    "https://ghproxy.net/$VERSION_URL_DIRECT?t=$timestamp",
                    // 2. kkgithub (Runner-up - Good fallback)
                    "https://raw.kkgithub.com/jacktheLad/PDFBooklet/main/version.json?t=$timestamp",
                    // 3. Direct (Baseline - For overseas/VPN)
                    "$VERSION_URL_DIRECT?t=$timestamp"
                )

                val result = raceRequests(urls)
                
                if (result != null) {
                    val appVersion = gson.fromJson(result, AppVersion::class.java)
                    if (isNewVersion(appVersion.version)) {
                        withContext(Dispatchers.Main) { onResult(UpdateState.Available(appVersion)) }
                    } else {
                        withContext(Dispatchers.Main) { onResult(UpdateState.NoUpdate) }
                    }
                } else {
                    withContext(Dispatchers.Main) { onResult(UpdateState.Error("Failed to connect to any update source")) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(UpdateState.Error("Check update error: ${e.message}")) }
            }
        }
    }
    
    private suspend fun raceRequests(urls: List<String>): String? = coroutineScope {
        val channel = Channel<String?>(urls.size)
        
        val jobs = urls.map { url ->
            launch {
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            channel.send(response.body?.string())
                        } else {
                            channel.send(null)
                        }
                    }
                } catch (e: Exception) {
                    channel.send(null)
                }
            }
        }
        
        var failures = 0
        var winner: String? = null
        
        repeat(urls.size) {
            val result = channel.receive()
            if (result != null && winner == null) {
                winner = result
                jobs.forEach { it.cancel() } // Cancel other slow requests
            } else {
                failures++
            }
        }
        
        winner
    }

    private fun isNewVersion(remoteVersionStr: String): Boolean {
        val remoteVersion = remoteVersionStr.removePrefix("v")
        val localVersion = BuildConfig.VERSION_NAME.removePrefix("v")
        
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
        scope.launch {
            try {
                val originalUrl = version.downloadUrl
                val candidates = mutableListOf<String>()
                
                // 1. ghproxy.net
                candidates.add("https://ghproxy.net/$originalUrl")
                
                // 2. kkgithub
                if (originalUrl.contains("raw.githubusercontent.com")) {
                    candidates.add(originalUrl.replace("raw.githubusercontent.com", "raw.kkgithub.com"))
                } else if (originalUrl.contains("github.com")) {
                    candidates.add(originalUrl.replace("github.com", "kkgithub.com"))
                }
                
                // 3. Direct
                candidates.add(originalUrl)

                // Race to find the fastest connected URL
                val bestUrl = raceForBestUrl(candidates) ?: originalUrl
                
                // Try the best URL first
                var downloadSuccess = performDownload(context, bestUrl, "PDFBooklet-v${version.version}.apk", onProgress, onError)
                
                // Fallback: If best URL fails, try others sequentially
                if (!downloadSuccess) {
                    for (url in candidates) {
                        if (url != bestUrl) {
                            downloadSuccess = performDownload(context, url, "PDFBooklet-v${version.version}.apk", onProgress, onError)
                            if (downloadSuccess) break
                        }
                    }
                }
                
                if (!downloadSuccess) {
                     withContext(Dispatchers.Main) { onError("所有下载通道均失败，请检查网络") }
                }

            } catch (e: Exception) {
                 withContext(Dispatchers.Main) { onError("Download error: ${e.message}") }
            }
        }
    }
    
    private suspend fun raceForBestUrl(urls: List<String>): String? = coroutineScope {
         val channel = Channel<String?>(urls.size)
         
         val jobs = urls.map { url ->
             launch {
                 try {
                     // Use HEAD request to check connectivity speed without downloading
                     val request = Request.Builder().url(url).head().build()
                     client.newCall(request).execute().use { response ->
                         if (response.isSuccessful) {
                             channel.send(url)
                         } else {
                             channel.send(null)
                         }
                     }
                 } catch (e: Exception) {
                     channel.send(null)
                 }
             }
         }
         
         var failures = 0
         var winner: String? = null
         
         repeat(urls.size) {
             val result = channel.receive()
             if (result != null && winner == null) {
                 winner = result
                 jobs.forEach { it.cancel() }
             } else {
                 failures++
             }
         }
         winner
    }
    
    private suspend fun performDownload(context: Context, url: String, filename: String, onProgress: (Float) -> Unit, onError: (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Use a dedicated client for download with longer timeouts
                val downloadClient = client.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS) // Increased to 60s for slow networks
                    .build()

                val request = Request.Builder().url(url).build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body ?: return@withContext false
                    
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
                            withContext(Dispatchers.Main) {
                                onProgress(totalRead.toFloat() / total.toFloat())
                            }
                        }
                    }
                    
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    
                    withContext(Dispatchers.Main) {
                        onProgress(1.0f)
                        installApk(context, file, onError)
                    }
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun installApk(context: Context, file: File, onError: (String) -> Unit) {
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
            onError("安装启动失败: ${e.message}\n请尝试在文件管理器中手动安装: ${file.absolutePath}")
        }
    }
}
