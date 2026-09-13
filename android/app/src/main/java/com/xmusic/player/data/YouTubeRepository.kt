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

    // Piped public instances change frequently. Keep a current fallback list and
    // refresh from TeamPiped's public-instance registry when possible.
    private val fallbackInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi.moomoo.me",
        "https://pipedapi.syncpundit.io",
        "https://api-piped.mha.fi",
        "https://piped-api.garudalinux.org",
        "https://piped-api.privacy.com.de",
        "https://api.piped.projectsegfau.lt",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.drgns.space"
    )

    private var discoveredInstances: List<String>? = null
    @Volatile private var bannedInstances: Set<String> = emptySet()

    private fun instances(): List<String> {
        discoveredInstances?.let { return it }
        val discovered = runCatching {
            val markdown = get(
                "https://raw.githubusercontent.com/TeamPiped/documentation/main/content/docs/public-instances/index.md",
                connectTimeout = 5000,
                readTimeout = 7000
            )
            val urlRegex = Regex("https://[^\\s|)]+")
            markdown.lineSequence()
                .filter { it.contains('|') }
                .mapNotNull { line ->
                    val columns = line.split('|')
                    if (columns.size < 3) null
                    else urlRegex.find(columns[2])?.value?.trimEnd('.', ',', ';')
                }
                .filter { it.startsWith("https://") }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())

        return (discovered + fallbackInstances).distinct().also { discoveredInstances = it }
    }


    fun search(query: String): List<Video> {
        require(query.isNotBlank()) { "Query kosong" }
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        var last: Exception? = null
        for (base in instances().filterNot { it in bannedInstances }) {
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
            } catch (e: Exception) {
                last = e
                // Remember dead/unreachable instances so later requests skip them quickly.
                if (bannedInstances.size > 3) bannedInstances = emptySet() else bannedInstances = bannedInstances + base
            }
        }
        throw last ?: IllegalStateException("Tidak ada resolver tersedia")
    }

    fun resolveAudio(videoId: String): VideoStreamResult {
        require(videoId.isNotBlank()) { "Video ID kosong" }
        var last: Exception? = null
        for (base in instances().filterNot { it in bannedInstances }) {
            try {
                val o = JSONObject(get("$base/streams/${URLEncoder.encode(videoId, Charsets.UTF_8.name())}"))
                val candidates = ArrayList<Stream>()
                fun collect(arr: JSONArray?, allowVideoMux: Boolean) {
                    if (arr == null) return
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        val url = a.optString("url").trim()
                        val mime = sanitizeMime(a.optString("mimeType"))
                        if (url.isBlank() || mime.isBlank() || isPlaceholder(url)) continue
                        val isAudio = mime.startsWith("audio/")
                        val isVideoMux = allowVideoMux && mime.startsWith("video/") && !a.optBoolean("videoOnly", true)
                        if (isAudio || isVideoMux) {
                            candidates += Stream(url, mime, a.optInt("bitrate", 0), a.optString("quality", ""))
                        }
                    }
                }
                collect(o.optJSONArray("audioStreams"), false)
                if (candidates.isEmpty()) collect(o.optJSONArray("videoStreams"), true)
                if (candidates.isEmpty()) error("Resolver tidak mengembalikan audio")

                // Prefer the highest-bitrate candidate whose URL actually responds,
                // then fall back to the next candidate if a URL is dead/expired.
                val sorted = candidates.sortedByDescending { it.bitrate }
                var success: Stream? = null
                var failed: Exception? = null
                for (s in sorted.take(4)) {
                    if (isStreamLive(s.url)) { success = s; break }
                    failed = java.io.IOException("Stream offline: HTTP unavailable")
                }
                if (success != null) return VideoStreamResult(success.url, success.mimeType)
                throw failed ?: error("Audio tidak dapat diputar")
            } catch (e: Exception) {
                last = e
                if (bannedInstances.size > 3) bannedInstances = emptySet() else bannedInstances = bannedInstances + base
            }
        }
        throw last ?: IllegalStateException("Audio tidak dapat di-resolve")
    }

    /** Strip codec parameters so ExoPlayer can map the exact base MIME type. */
    private fun sanitizeMime(mime: String): String = mime.substringBefore(';').trim()

    /** Piped sometimes returns non-playable placeholders instead of real stream URLs. */
    private fun isPlaceholder(url: String): Boolean {
        if (url == "HLS_MMUS" || url.startsWith("HLS_MMUS")) return true
        if (url.contains("youtube.com/watch") || url.contains("youtu.be/")) return true
        return url.endsWith(".m3u8") && !url.startsWith("https://") // local manifest path
    }

    /** Cheap existence probe: ranged GET of the first bytes must return 200/206. */
    private fun isStreamLive(url: String): Boolean = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            setRequestProperty("Range", "bytes=0-64")
        }
        try {
            val code = c.responseCode
            code == 200 || code == 206
        } finally { c.disconnect() }
    }.getOrDefault(false)

    private fun get(url: String, connectTimeout: Int = 7000, readTimeout: Int = 15000): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            useCaches = false
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
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
