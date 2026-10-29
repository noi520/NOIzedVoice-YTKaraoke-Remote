package com.noi.karaokehost

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class LoungeException(message: String) : Exception(message)

/**
 * Kotlin port ng YouTube Lounge protocol (hango sa pyytlounge 3.5).
 * Unofficial ito; puwedeng magbago ang YouTube anumang oras.
 */
class Lounge(
    private val prefs: SharedPreferences,
    private val deviceName: String,
    private val scope: CoroutineScope
) {
    var onPlayback: (suspend (String, Double, Double) -> Unit)? = null
    var onAd: (suspend (Boolean, Boolean) -> Unit)? = null
    var onConnection: (suspend (Boolean) -> Unit)? = null
    var onError: (suspend (String) -> Unit)? = null

    private val base = "https://www.youtube.com/api/lounge"
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val streamHttp = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()

    @Volatile private var screenId: String? = prefs.getString("screen_id", null)
    @Volatile private var loungeToken: String? = prefs.getString("lounge_token", null)
    private val deviceId: String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    @Volatile private var sid: String? = null
    @Volatile private var gsession: String? = null
    @Volatile private var lastEventId: String? = null
    @Volatile private var offset = 1
    @Volatile private var connectedFlag = false
    @Volatile private var activeCall: Call? = null
    private var job: Job? = null

    val paired: Boolean get() = screenId != null
    private val linked: Boolean get() = screenId != null && loungeToken != null
    val connected: Boolean get() = connectedFlag && sid != null && gsession != null

    private fun saveAuth() {
        prefs.edit().putString("screen_id", screenId).putString("lounge_token", loungeToken).apply()
    }

    private suspend fun postForm(url: String, form: Map<String, String>, client: OkHttpClient = http): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val fb = FormBody.Builder()
            form.forEach { (k, v) -> fb.add(k, v) }
            client.newCall(Request.Builder().url(url).post(fb.build()).build()).execute()
                .use { it.code to (it.body?.string().orEmpty()) }
        }

    // ---------- pairing ----------
    suspend fun pair(rawCode: String) {
        val code = rawCode.filter { it.isDigit() }
        if (code.isEmpty()) throw LoungeException("The TV code is empty")
        try {
            val (status, text) = postForm("$base/pairing/get_screen", mapOf("pairing_code" to code))
            if (status != 200) throw LoungeException("Could not pair. Check the TV code")
            val s = JSONObject(text).getJSONObject("screen")
            screenId = s.getString("screenId")
            loungeToken = s.getString("loungeToken")
            saveAuth()
        } catch (e: LoungeException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LoungeException("Could not pair. Check the TV code and the WiFi")
        }
    }

    suspend fun refreshAuth() {
        val id = screenId ?: throw LoungeException("Not paired yet")
        val (status, text) = postForm("$base/pairing/get_lounge_token_batch", mapOf("screen_ids" to id))
        if (status != 200) throw LoungeException("Could not refresh the TV login ($status)")
        val s = JSONObject(text).getJSONArray("screens").getJSONObject(0)
        screenId = s.getString("screenId")
        loungeToken = s.getString("loungeToken")
        saveAuth()
    }

    suspend fun unpair() {
        job?.cancel(); job = null
        activeCall?.cancel()
        screenId = null; loungeToken = null
        sid = null; gsession = null; lastEventId = null
        prefs.edit().remove("screen_id").remove("lounge_token").apply()
        setConnected(false)
    }

    // ---------- connect loop (backoff 2s, 5s, 10s) ----------
    fun start() {
        job?.cancel()
        activeCall?.cancel()
        job = scope.launch { runLoop() }
    }

    private suspend fun setConnected(flag: Boolean) {
        if (flag != connectedFlag) {
            connectedFlag = flag
            onConnection?.invoke(flag)
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        val backoff = longArrayOf(2000, 5000, 10000)
        while (true) {
            try {
                if (attempt > 0 || !linked) {
                    try { refreshAuth() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* subukan pa rin */ }
                }
                if (connectOnce()) {
                    attempt = 0
                    setConnected(true)
                    subscribe() // naka-block hanggang mawala ang connection
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // babalik sa backoff
            }
            setConnected(false)
            val d = backoff[minOf(attempt, backoff.size - 1)]
            attempt++
            if (attempt == backoff.size + 1) onError?.invoke("Lost connection to the TV. Reconnecting...")
            delay(d)
        }
    }

    // ---------- chunk parser ----------
    private class Chunker {
        var remaining = 0
        val sb = StringBuilder()
        fun feed(line: String): JSONArray? {
            if (remaining <= 0) {
                remaining = line.trim().toIntOrNull() ?: return null
                sb.setLength(0)
                return null
            }
            sb.append(line)
            remaining -= line.length + 1
            if (remaining <= 0) {
                val arr = try { JSONArray(sb.toString()) } catch (e: Exception) { null }
                sb.setLength(0); remaining = 0
                return arr
            }
            return null
        }
    }

    private fun mapState(s: String): String = when (s.trim()) {
        "1", "3", "5" -> "playing"
        "2" -> "paused"
        "0" -> "ended" // YT labels this "ended"
        "1081" -> "ad"
        "1082" -> "ad_skipped"
        else -> "stopped"
    }

    private suspend fun process(events: JSONArray) {
        for (i in 0 until events.length()) {
            val ev = events.optJSONArray(i) ?: continue
            val inner = ev.optJSONArray(1) ?: continue
            when (inner.optString(0)) {
                "c" -> sid = inner.optString(1)
                "S" -> gsession = inner.optString(1)
                "onStateChange" -> inner.optJSONObject(1)?.let {
                    onPlayback?.invoke(
                        mapState(it.optString("state")),
                        it.optString("currentTime").toDoubleOrNull() ?: 0.0,
                        it.optString("duration").toDoubleOrNull() ?: 0.0
                    )
                }
                "nowPlaying" -> inner.optJSONObject(1)?.let {
                    onPlayback?.invoke(
                        if (it.has("state")) mapState(it.optString("state")) else "stopped",
                        it.optString("currentTime").toDoubleOrNull() ?: 0.0,
                        it.optString("duration").toDoubleOrNull() ?: 0.0
                    )
                }
                "adPlaying" -> inner.optJSONObject(1)?.let {
                    onAd?.invoke(true, it.optString("isSkipEnabled") == "true")
                }
                "onAdStateChange" -> inner.optJSONObject(1)?.let {
                    onAd?.invoke(it.optString("adState") != "1082", it.optString("isSkipEnabled") == "true")
                }
                "loungeScreenDisconnected" -> {
                    sid = null; gsession = null; lastEventId = null
                    loungeToken = null
                    onConnection?.let { cb -> connectedFlag = false; cb.invoke(false) }
                }
            }
        }
        if (events.length() > 0) {
            events.optJSONArray(events.length() - 1)?.let { lastEventId = it.opt(0)?.toString() }
        }
    }

    private suspend fun connectOnce(): Boolean {
        val token = loungeToken ?: return false
        sid = null; gsession = null; lastEventId = null
        val form = mapOf(
            "app" to "web", "mdx-version" to "3", "name" to deviceName, "id" to deviceId,
            "device" to "REMOTE_CONTROL", "capabilities" to "que,dsdtr,atp,vsp",
            "magnaKey" to "cloudPairedDevice", "ui" to "false",
            "deviceContext" to "user_agent=dunno&window_width_points=&window_height_points=&os_name=android&ms=",
            "theme" to "cl", "loungeIdToken" to token
        )
        val (status, text) = postForm("$base/bc/bind?RID=1&VER=8&CVER=1&auth_failure_option=send_error", form)
        if (status == 401) { loungeToken = null; return false }
        if (status != 200) return false
        val ch = Chunker()
        for (line in text.lines()) ch.feed(line)?.let { process(it) }
        offset = 1
        return sid != null && gsession != null
    }

    private fun common(): Map<String, String> = buildMap {
        put("name", deviceName)
        loungeToken?.let { put("loungeIdToken", it) }
        sid?.let { put("SID", it) }
        lastEventId?.let { put("AID", it) }
        gsession?.let { put("gsessionid", it) }
        put("device", "REMOTE_CONTROL"); put("app", "youtube-desktop"); put("VER", "8"); put("v", "2")
    }

    private fun sessionOk(code: Int, text: String): Boolean {
        if (code == 400 && text.contains("Unknown SID")) { sid = null; gsession = null; lastEventId = null; return false }
        if (code == 410 && text.contains("Gone")) { sid = null; gsession = null; lastEventId = null; return false }
        if (code == 401 && text.contains("Expired")) { sid = null; gsession = null; lastEventId = null; loungeToken = null; return false }
        return true
    }

    private suspend fun subscribe() = withContext(Dispatchers.IO) {
        val url = "$base/bc/bind".toHttpUrl().newBuilder().apply {
            common().forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("RID", "rpc"); addQueryParameter("CI", "0"); addQueryParameter("TYPE", "xmlhttp")
        }.build()
        val call = streamHttp.newCall(Request.Builder().url(url).get().build())
        activeCall = call
        call.execute().use { resp ->
            if (!sessionOk(resp.code, resp.message)) return@use
            val src = resp.body?.source() ?: return@use
            val ch = Chunker()
            while (isActive) {
                val line = src.readUtf8Line() ?: break
                ch.feed(line)?.let { process(it) }
                if (sid == null) break
            }
        }
    }

    // ---------- commands ----------
    private suspend fun rawCommand(name: String, params: Map<String, String>): Boolean {
        if (!connected) throw LoungeException("Not connected to the TV")
        val body = LinkedHashMap<String, String>()
        body["count"] = "1"; body["ofs"] = offset.toString(); body["req0__sc"] = name
        params.forEach { (k, v) -> body["req0_$k"] = v }
        offset++
        val url = "$base/bc/bind".toHttpUrl().newBuilder().apply {
            common().forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("RID", offset.toString())
        }.build().toString()
        val (status, text) = postForm(url, body)
        if (!sessionOk(status, text)) return false
        if (status !in 200..299) throw LoungeException("The TV returned an error ($status)")
        return true
    }

    /** Sa error: refresh auth, hintayin ang reconnect, subukan ulit nang isang beses. */
    private suspend fun cmd(name: String, params: Map<String, String> = emptyMap()) {
        var last: String? = null
        for (attempt in 0..1) {
            try {
                if (rawCommand(name, params)) return
                last = "The TV rejected the command"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e.message
            }
            if (attempt == 0) {
                try { refreshAuth() } catch (e: CancellationException) { throw e } catch (e: Exception) { }
                for (i in 0 until 50) { if (connected) break; delay(100) }
            }
        }
        throw LoungeException(last ?: "Cannot reach the TV")
    }

    suspend fun playVideo(videoId: String) = cmd("setPlaylist", mapOf("videoId" to videoId))
    suspend fun pause() = cmd("pause")
    suspend fun play() = cmd("play")
    suspend fun skipAd() = cmd("skipAd")
}
