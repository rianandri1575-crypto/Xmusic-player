package com.xmusic.player.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val repoUrl: String = "rianandri1575-crypto/Xmusic-player") {
    data class ReleaseInfo(
        val tag: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val assetUrl: String?,
        val sizeBytes: Long
    )

    fun check(): ReleaseInfo? = runCatching {
        val url = URL("https://api.github.com/repos/$repoUrl/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "XMusic-Update")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return@runCatching null
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(text)
            val tag = obj.optString("tag_name").ifBlank { obj.optString("tag_name", obj.optString("name", "v0.0.0")) }
            val name = obj.optString("name", "")
            val body = obj.optString("body", "")
            val htmlUrl = obj.optString("html_url", "")
            val assets = obj.optJSONArray("assets")
            val assetUrl = if (assets != null && assets.length() > 0) assets.getJSONObject(0).optString("browser_download_url", null) else null
            val sizeBytes = if (assets != null && assets.length() > 0) assets.getJSONObject(0).optLong("size", 0) else 0
            ReleaseInfo(tag, name, body, htmlUrl, assetUrl, sizeBytes)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
