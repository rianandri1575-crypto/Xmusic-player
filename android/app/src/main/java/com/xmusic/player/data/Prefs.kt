package com.xmusic.player.data

import android.content.Context
import com.xmusic.player.audio.CrossoverConfig
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("xmusic", Context.MODE_PRIVATE)

    var accent: Long
        get() = runCatching { p.getLong("accent", 0xFF00D4FF) }.getOrDefault(0xFF00D4FF)
        set(v) = p.edit().putLong("accent", v).apply()

    var supporter: Boolean
        get() = runCatching { p.getBoolean("supporter", false) }.getOrDefault(false)
        set(v) = p.edit().putBoolean("supporter", v).apply()

    fun saveEq(gains: FloatArray) {
        p.edit().putString("eq", gains.joinToString(",")).apply()
    }

    fun loadEq(): FloatArray {
        val s = p.getString("eq", null) ?: return FloatArray(31)
        return s.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            .let { if (it.size == 31) it else FloatArray(31) }
    }

    fun saveCrossover(c: CrossoverConfig) {
        p.edit().putString(
            "crossover",
            "${c.enabled},${c.lowHz},${c.highHz},${c.slopeDb},${c.lowGainDb},${c.midGainDb},${c.highGainDb}"
        ).apply()
    }

    fun loadCrossover(): CrossoverConfig {
        val s = p.getString("crossover", null) ?: return CrossoverConfig()
        return try {
            val a = s.split(",")
            CrossoverConfig(
                enabled = a.getOrNull(0)?.toBooleanStrictOrNull() ?: true,
                lowHz = a.getOrNull(1)?.toFloatOrNull() ?: 80f,
                highHz = a.getOrNull(2)?.toFloatOrNull() ?: 2500f,
                slopeDb = a.getOrNull(3)?.toIntOrNull() ?: 24,
                lowGainDb = a.getOrNull(4)?.toFloatOrNull() ?: 0f,
                midGainDb = a.getOrNull(5)?.toFloatOrNull() ?: 0f,
                highGainDb = a.getOrNull(6)?.toFloatOrNull() ?: 0f
            )
        } catch (_: Exception) { CrossoverConfig() }
    }

    fun isFavorite(id: String): Boolean = favorites().any { it.id == id }

    fun toggleFavorite(video: YouTubeRepository.Video): Boolean {
        val current = favorites().toMutableList()
        val index = current.indexOfFirst { it.id == video.id }
        val added = index < 0
        if (added) current.add(0, video) else current.removeAt(index)
        saveVideos("favorites", current.take(500))
        return added
    }

    fun favorites(): List<YouTubeRepository.Video> = loadVideos("favorites")

    fun addHistory(video: YouTubeRepository.Video) {
        val current = loadVideos("history").filterNot { it.id == video.id }.toMutableList()
        current.add(0, video)
        saveVideos("history", current.take(100))
    }

    fun history(): List<YouTubeRepository.Video> = loadVideos("history")

    private fun saveVideos(key: String, videos: List<YouTubeRepository.Video>) {
        val array = JSONArray()
        videos.forEach { v ->
            array.put(JSONObject().apply {
                put("id", v.id); put("title", v.title); put("uploader", v.uploader)
                put("duration", v.duration); put("thumbnail", v.thumbnail)
            })
        }
        p.edit().putString(key, array.toString()).apply()
    }

    private fun loadVideos(key: String): List<YouTubeRepository.Video> {
        val raw = p.getString(key, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isNotBlank()) add(
                        YouTubeRepository.Video(
                            id, o.optString("title", "Unknown"),
                            o.optString("uploader", "Unknown artist"),
                            o.optLong("duration", 0), o.optString("thumbnail", "")
                        )
                    )
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
