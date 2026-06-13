package com.notzeetaa.emuhub

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

suspend fun downloadAsset(context: Context, release: GithubRelease, asset: GithubAsset) {
    downloadFileWithProgress(context, asset.downloadUrl, "${release.tagName}_${asset.name}")
}

suspend fun downloadComponent(context: Context, component: Component) {
    val fileName = component.remoteUrl.substringAfterLast("/")
    downloadFileWithProgress(context, component.remoteUrl, fileName)
}

private suspend fun downloadFileWithProgress(context: Context, url: String, fileName: String) {
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "EmuHub-Android/1.0")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "HTTP error: ${response.code}", Toast.LENGTH_LONG).show()
                    DownloadsManager.failDownload(fileName)
                }
                return@withContext
            }

            val contentLength = response.body?.contentLength() ?: -1
            withContext(Dispatchers.Main) { DownloadsManager.startDownload(fileName, if (contentLength > 0) contentLength else 1L) }

            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No data received", Toast.LENGTH_SHORT).show()
                    DownloadsManager.failDownload(fileName)
                }
                return@withContext
            }

            val outputPath: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Cannot create file in Downloads", Toast.LENGTH_SHORT).show()
                        DownloadsManager.failDownload(fileName)
                    }
                    return@withContext
                }
                resolver.openOutputStream(uri)?.use { outputStream ->
                    if (contentLength > 0) {
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            withContext(Dispatchers.Main) { DownloadsManager.updateProgress(fileName, totalRead) }
                        }
                    } else {
                        inputStream.copyTo(outputStream)
                        withContext(Dispatchers.Main) { DownloadsManager.updateProgress(fileName, 1024 * 1024) }
                    }
                } ?: run {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Cannot write file", Toast.LENGTH_SHORT).show()
                        DownloadsManager.failDownload(fileName)
                    }
                    return@withContext
                }
                outputPath = uri.toString()
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Cannot create Downloads folder", Toast.LENGTH_SHORT).show()
                        DownloadsManager.failDownload(fileName)
                    }
                    return@withContext
                }
                val outputFile = File(downloadsDir, fileName)
                FileOutputStream(outputFile).use { outputStream ->
                    if (contentLength > 0) {
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            withContext(Dispatchers.Main) { DownloadsManager.updateProgress(fileName, totalRead) }
                        }
                    } else {
                        inputStream.copyTo(outputStream)
                        withContext(Dispatchers.Main) { DownloadsManager.updateProgress(fileName, 1024 * 1024) }
                    }
                }
                outputPath = outputFile.absolutePath
            }

            val finalSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (contentLength > 0) contentLength else 0L
            } else {
                File(outputPath).length()
            }

            withContext(Dispatchers.Main) {
                DownloadsManager.completeDownload(fileName, outputPath, finalSize)
                Toast.makeText(context, "Download complete: $fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                DownloadsManager.failDownload(fileName)
            }
        }
    }
}