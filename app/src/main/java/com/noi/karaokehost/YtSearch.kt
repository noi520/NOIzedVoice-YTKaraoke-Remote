package com.noi.karaokehost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** YouTube search na walang API key: binabasa ang results page (ytInitialData). May cache. */
object YtSearch {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val songs = ConcurrentHashMap<String, Song>()
    private val cache = object : LinkedHashMap<String, Pair<Long, List<Song>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Song>>>?) = size > 100
    }
    private val idRe = Regex("^[a-zA-Z0-9_-]{11}$")

    fun lookup(videoId: String): Song? = songs[videoId]

    suspend fun search(raw: String): List<Song> {
        var q = raw.trim().split(Regex("\\s+")).joinToString(" ")
        if (!q.contains("karaoke", ignoreCase = true)) q += " karaoke"
        val key = q.lowercase()
        synchronized(cache) { cache[key] }?.let {
            if (System.currentTimeMillis() - it.first < 600_000) return it.second
        }
        val list = parse(fetch(q))
        synchronized(cache) { cache[key] = System.currentTimeMillis() to list }
        list.forEach { songs[it.videoId] = it }
        return list
    }

    private suspend fun fetch(q: String): String = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8") + "&sp=EgIQAQ%3D%3D"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "SOCS=CAI")
            .build()
        http.newCall(req).execute().use {
            if (!it.isSuccessful) throw IOException("YouTube ${it.code}")
            it.body?.string().orEmpty()
        }
    }

    private fun parse(html: String): List<Song> {
        val marker = html.indexOf("ytInitialData")
        if (marker < 0) throw IOException("The YouTube search format changed")
        val start = html.indexOf('{', marker)
        val end = html.indexOf(";</script>", start)
        if (start < 0 || end < 0) throw IOException("The YouTube search format changed")
        val out = LinkedHashMap<String, Song>()
        walk(JSONObject(html.substring(start, end)), out)
        return out.values.take(20)
    }

    private fun walk(n: Any?, out: MutableMap<String, Song>) {
        when (n) {
            is JSONObject -> {
                n.optJSONObject("videoRenderer")?.let { toSong(it) }?.let { out.putIfAbsent(it.videoId, it) }
                for (k in n.keys()) if (k != "videoRenderer") walk(n.opt(k), out)
            }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), out)
        }
    }

    private fun toSong(v: JSONObject): Song? {
        val id = v.optString("videoId")
        if (!idRe.matches(id)) return null
        val len = v.optJSONObject("lengthText")?.optString("simpleText") ?: return null // live = walang length
        val title = v.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: "(untitled)"
        val sec = len.split(":").fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }
        return Song(id, title, "https://i.ytimg.com/vi/$id/mqdefault.jpg", sec)
    }
}
