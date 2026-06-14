package com.notzeetaa.emuhub

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

private const val TAG = "EmuHubDownload"

suspend fun downloadAsset(context: Context, release: GithubRelease, asset: GithubAsset) {
    downloadFileWithProgress(context, asset.downloadUrl, "${release.tagName}_${asset.name}")
}

suspend fun downloadComponent(context: Context, component: Component) {
    val fileName = component.remoteUrl.substringAfterLast("/")
    downloadFileWithProgress(context, component.remoteUrl, fileName)
}

private suspend fun getUniqueFileName(context: Context, folderUri: Uri?, desiredName: String): String {
    val lastDot = desiredName.lastIndexOf('.')
    val nameWithoutExt = if (lastDot > 0) desiredName.substring(0, lastDot) else desiredName
    val extension = if (lastDot > 0) desiredName.substring(lastDot) else ""

    var counter = 1
    var newName = desiredName
    while (true) {
        val exists = if (folderUri != null && DocumentsContract.isTreeUri(folderUri)) {
            val folderDoc = DocumentFile.fromTreeUri(context, folderUri)
            folderDoc?.findFile(newName) != null
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, arrayOf(newName), null)
                val exists = cursor?.count ?: 0 > 0
                cursor?.close()
                exists
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(downloadsDir, newName).exists()
            }
        }
        if (!exists) return newName
        newName = "$nameWithoutExt ($counter)$extension"
        counter++
    }
}

private suspend fun getOutputUri(context: Context, uniqueFileName: String): Uri? = withContext(Dispatchers.IO) {
    val folderUriString = SettingsManager.getDownloadFolderUri()
    val folderUri = if (folderUriString != null) Uri.parse(folderUriString) else null

    if (folderUri != null) {
        context.contentResolver.takePersistableUriPermission(
            folderUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val folderDoc = DocumentFile.fromTreeUri(context, folderUri)
        if (folderDoc != null && folderDoc.canWrite()) {
            return@withContext folderDoc.createFile("application/octet-stream", uniqueFileName)?.uri
        } else {
            Log.e(TAG, "Cannot write to custom folder")
            return@withContext null
        }
    } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) return@withContext null
            val file = File(downloadsDir, uniqueFileName)
            Uri.fromFile(file)
        }
    }
}

private suspend fun downloadFileWithProgress(context: Context, url: String, originalDesiredName: String) {
    withContext(Dispatchers.IO) {
        val folderUriString = SettingsManager.getDownloadFolderUri()
        val folderUri = if (folderUriString != null) Uri.parse(folderUriString) else null
        val uniqueFileName = getUniqueFileName(context, folderUri, originalDesiredName)

        // Show a toast that download has started
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Download started: $uniqueFileName", Toast.LENGTH_SHORT).show()
        }

        withContext(Dispatchers.Main) {
            DownloadsManager.startDownload(uniqueFileName, 1L)
        }

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
                    DownloadsManager.failDownload(uniqueFileName)
                }
                return@withContext
            }

            val contentLength = response.body?.contentLength() ?: -1
            if (contentLength > 0) {
                withContext(Dispatchers.Main) {
                    DownloadsManager.startDownload(uniqueFileName, contentLength)
                }
            }

            val inputStream = response.body?.byteStream()
            if (inputStream == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No data received", Toast.LENGTH_SHORT).show()
                    DownloadsManager.failDownload(uniqueFileName)
                }
                return@withContext
            }

            val outputUri = getOutputUri(context, uniqueFileName)
            if (outputUri == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Cannot create output file", Toast.LENGTH_SHORT).show()
                    DownloadsManager.failDownload(uniqueFileName)
                }
                return@withContext
            }

            val outputPath: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri.scheme == "content") {
                context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    if (contentLength > 0) {
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            withContext(Dispatchers.Main) {
                                DownloadsManager.updateProgress(uniqueFileName, totalRead)
                            }
                        }
                    } else {
                        inputStream.copyTo(outputStream)
                        withContext(Dispatchers.Main) {
                            DownloadsManager.updateProgress(uniqueFileName, 1024 * 1024)
                        }
                    }
                } ?: throw Exception("Cannot open output stream")
                outputPath = outputUri.toString()
            } else {
                val outputFile = File(outputUri.path ?: throw Exception("No path"))
                FileOutputStream(outputFile).use { outputStream ->
                    if (contentLength > 0) {
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            withContext(Dispatchers.Main) {
                                DownloadsManager.updateProgress(uniqueFileName, totalRead)
                            }
                        }
                    } else {
                        inputStream.copyTo(outputStream)
                        withContext(Dispatchers.Main) {
                            DownloadsManager.updateProgress(uniqueFileName, 1024 * 1024)
                        }
                    }
                }
                outputPath = outputFile.absolutePath
            }

            val finalSize = if (contentLength > 0) contentLength else 0L
            withContext(Dispatchers.Main) {
                DownloadsManager.completeDownload(uniqueFileName, outputPath, finalSize)
                Toast.makeText(context, "Download complete: $uniqueFileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                DownloadsManager.failDownload(uniqueFileName)
            }
        }
    }
}