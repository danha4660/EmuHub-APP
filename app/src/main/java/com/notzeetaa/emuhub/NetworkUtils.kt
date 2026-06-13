package com.notzeetaa.emuhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

data class GithubRelease(val tagName: String, val name: String, val assets: List<GithubAsset>)
data class GithubAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)
data class Component(val type: String, val verName: String, val verCode: String, val remoteUrl: String)

suspend fun fetchGithubReleasesFromUrl(apiUrl: String): List<GithubRelease> = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val request = Request.Builder()
        .url(apiUrl)
        .header("Accept", "application/vnd.github.v3+json")
        .build()
    try {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyList()
        val json = response.body?.string() ?: return@withContext emptyList()
        val releasesArray = JSONArray(json)
        (0 until releasesArray.length()).mapNotNull { idx ->
            val obj = releasesArray.getJSONObject(idx)
            val tagName = obj.getString("tag_name")
            val name = obj.getString("name").ifEmpty { tagName }
            val assets = (0 until obj.getJSONArray("assets").length()).map { assetIdx ->
                val a = obj.getJSONArray("assets").getJSONObject(assetIdx)
                GithubAsset(a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
            }
            GithubRelease(tagName, name, assets)
        }.sortByVersionDescending { it.tagName }
    } catch (e: Exception) { emptyList() }
}

suspend fun fetchTurnipReleases(source: String, adrenoSeries: String): List<GithubRelease> {
    val repoUrl = when (source) {
        "StevenMXZ" -> "https://api.github.com/repos/StevenMXZ/Adreno-Tools-Drivers/releases"
        "whitebelyash" -> "https://api.github.com/repos/whitebelyash/AdrenoToolsDrivers/releases"
        else -> return emptyList()
    }
    val allReleases = fetchGithubReleasesFromUrl(repoUrl)
    if (allReleases.isEmpty()) return emptyList()

    val filtered = when (adrenoSeries) {
        "8xx" -> allReleases.filter { release ->
            when (source) {
                "StevenMXZ" -> release.name.contains("Turnip Gen8", true) || release.tagName.contains("Turnip Gen8", true)
                else -> release.name.contains("A8XX", true) || release.tagName.contains("A8XX", true)
            }
        }
        "6xx", "7xx" -> allReleases.filter { release ->
            when (source) {
                "StevenMXZ" -> release.name.contains("Turnip v26", true) || release.tagName.contains("v26", true)
                else -> release.name.contains("Mesa Turnip v26", true) || release.tagName.contains("Mesa Turnip v26", true)
            }
        }
        else -> allReleases.filter { it.name.contains("Turnip", true) }
    }
    return if (filtered.isNotEmpty()) filtered else allReleases.filter { it.name.contains("Turnip", true) }
        .sortByVersionDescending { it.tagName }
}

suspend fun loadQualcommDriver(): GithubRelease? {
    val releases = fetchGithubReleasesFromUrl("https://api.github.com/repos/StevenMXZ/Adreno-Tools-Drivers/releases")
    return releases.find { it.name.contains("Qualcomm Driver", true) }
}

suspend fun fetchComponentsFromUrl(): Map<String, List<Component>> = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val request = Request.Builder().url("https://raw.githubusercontent.com/WinNative-Emu/Components/refs/heads/main/contents.json").build()
    try {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyMap()
        val jsonArray = JSONArray(response.body?.string() ?: return@withContext emptyMap())
        val map = mutableMapOf<String, MutableList<Component>>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val comp = Component(obj.getString("type"), obj.getString("verName"), obj.getString("verCode"), obj.getString("remoteUrl"))
            map.getOrPut(comp.type) { mutableListOf() }.add(comp)
        }
        map.forEach { (_, list) -> list.sortByVersionDescending { it.verName } }
        map
    } catch (e: Exception) { emptyMap() }
}

// Version sorting helper
private fun versionCompare(v1: String, v2: String): Int {
    fun parseVersion(v: String): List<Int> = v.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
    val parts1 = parseVersion(v1)
    val parts2 = parseVersion(v2)
    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val num1 = parts1.getOrNull(i) ?: 0
        val num2 = parts2.getOrNull(i) ?: 0
        if (num1 != num2) return num1.compareTo(num2)
    }
    return 0
}

private fun <T> List<T>.sortByVersionDescending(selector: (T) -> String): List<T> =
    sortedWith { a, b -> versionCompare(selector(b), selector(a)) }