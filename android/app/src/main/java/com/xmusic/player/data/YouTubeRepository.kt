package com.xmusic.player.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Replaceable online discovery/resolver layer. */
class YouTubeRepository {
    data class Video(val id: String, val title: String, val uploader: String, val duration: Long, val thumbnail: String)
    data class Stream(val url: String, val mimeType: String, val bitrate: Int, val quality: String)
    data class VideoStreamResult(val url: String, val mimeType: String)

    private val instances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.drgns.space"
    )

    fun search(query: String): List<Video> {
        require(query.isNotBlank()) { "Query kosong" }
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        var last: Exception? = null
        for (base in instances) {
            try {
                val arr = JSONArray(get("$base/search?q=$q&filter=music_songs"))
                val out = ArrayList<Video>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val rawId = o.optString("id")
                    val id = rawId.ifBlank { o.optString("url").substringAfter("v=", "") }
                    if (id.isBlank()) continue
                    out += Video(id, clean(o.optString("title"), "Unknown"), clean(o.optString("uploaderName"), "Unknown artist"), o.optLong("duration", 0), o.optString("thumbnail", ""))
                }
                return out.distinctBy { it.id }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Tidak ada resolver tersedia")
    }

    fun resolveAudio(videoId: String): VideoStreamResult {
        require(videoId.isNotBlank()) { "Video ID kosong" }
        var last: Exception? = null
        for (base in instances) {
            try {
                val o = JSONObject(get("$base/streams/${URLEncoder.encode(videoId, Charsets.UTF_8.name())}"))
                val audio = o.optJSONArray("audioStreams") ?: JSONArray()
                val candidates = ArrayList<Stream>()
                for (i in 0 until audio.length()) {
                    val a = audio.optJSONObject(i) ?: continue
                    val url = a.optString("url")
                    val mime = a.optString("mimeType")
                    if (url.isBlank() || !mime.startsWith("audio/")) continue
                    candidates += Stream(url, mime, a.optInt("bitrate", 0), a.optString("quality", ""))
                }
                val best = candidates.maxWithOrNull(compareBy<Stream> { it.bitrate }.thenBy { it.quality.length })
                    ?: error("Resolver tidak mengembalikan audio")
                return VideoStreamResult(best.url, best.mimeType)
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Audio tidak dapat di-resolve")
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 15000
            useCaches = false
            setRequestProperty("User-Agent", "XMusic/2.2")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun clean(value: String, fallback: String): String = value.trim().ifBlank { fallback }
}
