package com.notzeetaa.emuhub

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

private const val PREFS_SETTINGS = "emu_hub_settings"
private const val KEY_DOWNLOAD_FOLDER_URI = "download_folder_uri"

object SettingsManager {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    fun getDownloadFolderUri(): String? = prefs.getString(KEY_DOWNLOAD_FOLDER_URI, null)

    fun setDownloadFolderUri(uriString: String) {
        prefs.edit().putString(KEY_DOWNLOAD_FOLDER_URI, uriString).apply()
    }

    fun clearDownloadFolder() {
        prefs.edit().remove(KEY_DOWNLOAD_FOLDER_URI).apply()
    }
}