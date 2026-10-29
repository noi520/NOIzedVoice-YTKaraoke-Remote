package com.noi.karaokehost

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class EngineError(val code: Int, message: String) : Exception(message)

/** Queue + playback logic (port ng Player/QueueManager ng backend). Nasa phone na lahat. */
object Engine {
    const val PORT = 8080

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playLock = Mutex()
    private val qLock = Any()
    private var started = false
    private lateinit var prefs: SharedPreferences
    private lateinit var lounge: Lounge
    private var server: GuestServer? = null
    var roomToken: String = ""
        private set

    var queue by mutableStateOf<List<Song>>(emptyList())
    var nowPlaying by mutableStateOf<Song?>(null)
    var playback by mutableStateOf("stopped")
    var locked by mutableStateOf(false)
    var adPlaying by mutableStateOf(false)
    var adSkippable by mutableStateOf(false)
    var paired by mutableStateOf(false)
    var tvConnected by mutableStateOf(false)
    var guests by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)

    private var lastPlay = 0L
    private var endedFor: String? = null
    private val seen = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun init(app: Application) {
        if (started) return
        started = true
        prefs = app.getSharedPreferences("karaoke", Context.MODE_PRIVATE)
        roomToken = prefs.getString("room_token", null) ?: run {
            val b = ByteArray(12).also { SecureRandom().nextBytes(it) }
            Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                .also { prefs.edit().putString("room_token", it).apply() }
        }
        lounge = Lounge(prefs, "NOIzedVoice", scope)
        lounge.onPlayback = { s, c, d -> onPlayback(s, c, d) }
        lounge.onAd = { active, skippable -> setAd(active, skippable) }
        lounge.onConnection = { c -> onConnection(c) }
        lounge.onError = { m -> message = m }
        paired = lounge.paired
        if (paired) lounge.start()
    }

    fun startServer(ctx: Context) {
        if (server != null) return
        try {
            server = GuestServer(ctx.applicationContext).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: IOException) {
            server = null
            message = "Could not start the guest server (port $PORT)"
        }
    }

    fun stopServer() { server?.stop(); server = null }

    // ---------- network info ----------
    fun localIp(): String? {
        val cands = ArrayList<Pair<String, String>>()
        val ifs = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        for (ni in ifs) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses.toList()) {
                if (a is Inet4Address && a.isSiteLocalAddress) cands.add(ni.name to (a.hostAddress ?: continue))
            }
        }
        return (cands.firstOrNull { it.first.startsWith("wlan") } ?: cands.firstOrNull())?.second
    }

    fun guestUrl(): String = "http://${localIp() ?: "localhost"}:$PORT/guest?room=$roomToken"

    fun touchGuest(ip: String) {
        val now = SystemClock.elapsedRealtime()
        seen[ip] = now
        seen.entries.removeAll { now - it.value > 10_000 }
        guests = seen.size
    }

    // ---------- pairing ----------
    suspend fun pair(code: String) {
        lounge.pair(code)
        paired = true
        lounge.start()
    }

    suspend fun unpair() {
        lounge.unpair()
        paired = false; tvConnected = false; playback = "stopped"
    }

    // ---------- queue ----------
    private inline fun <T> q(block: () -> T): T = synchronized(qLock) { block() }

    private fun contains(id: String) = nowPlaying?.videoId == id || queue.any { it.videoId == id }

    suspend fun add(song: Song, byHost: Boolean) {
        if (!byHost && locked) throw EngineError(423, "Adding songs is locked right now")
        q {
            if (contains(song.videoId)) throw EngineError(409, "This song is already in the queue")
            queue = queue + song
        }
        if (nowPlaying == null && lounge.connected) {
            try { advance() } catch (e: LoungeException) { /* nasa queue pa rin */ }
        }
    }

    fun remove(id: String) = q {
        val n = queue.size
        queue = queue.filter { it.videoId != id }
        if (queue.size == n) throw EngineError(404, "This song is not in the queue")
    }

    /** position: 1-based (1 = susunod) */
    fun reorder(id: String, position: Int) = q {
        val i = queue.indexOfFirst { it.videoId == id }
        if (i < 0) throw EngineError(404, "This song is not in the queue")
        val l = queue.toMutableList()
        val item = l.removeAt(i)
        l.add((position - 1).coerceIn(0, l.size), item)
        queue = l
    }

    fun playNext(id: String) = reorder(id, 1)
    fun clear() = q { queue = emptyList() }

    // ---------- playback ----------
    private fun setAd(active: Boolean, skippable: Boolean) {
        adPlaying = active
        adSkippable = active && skippable
    }

    private suspend fun advance(stopTvIfEmpty: Boolean = false) {
        playLock.withLock {
            val nxt = q { queue.firstOrNull() }
            if (nxt == null) {
                val had = nowPlaying != null
                nowPlaying = null; playback = "stopped"; setAd(false, false)
                if (had && stopTvIfEmpty) {
                    try { lounge.pause() } catch (e: LoungeException) { }
                }
                return
            }
            try {
                lounge.playVideo(nxt.videoId)
            } catch (e: LoungeException) {
                message = "Could not play the song on the TV. Is the TV on and paired?"
                throw e
            }
            q { queue = queue.filter { it.videoId != nxt.videoId } }
            nowPlaying = nxt
            lastPlay = SystemClock.elapsedRealtime()
            endedFor = null
            setAd(false, false)
            playback = "playing"
        }
    }

    suspend fun skip() = advance(true)

    suspend fun pause() { lounge.pause(); playback = "paused" }
    suspend fun resume() { lounge.play(); playback = "playing" }

    suspend fun skipAd() {
        if (!adPlaying) throw EngineError(409, "No ad is playing")
        if (!adSkippable) throw EngineError(409, "The ad cannot be skipped yet")
        lounge.skipAd()
    }

    // ---------- events galing sa TV ----------
    private suspend fun onPlayback(state: String, cur: Double, dur: Double) {
        when (state) {
            "ended" -> {
                val now = nowPlaying ?: return
                if (SystemClock.elapsedRealtime() - lastPlay < 3000) return
                if (dur > 0 && cur < dur - 5) return
                if (endedFor == now.videoId) return
                endedFor = now.videoId
                try { advance() } catch (e: LoungeException) { }
            }
            "playing", "paused" -> { setAd(false, false); playback = state }
            "stopped" -> playback = "stopped"
        }
    }

    private suspend fun onConnection(c: Boolean) {
        tvConnected = c
        if (c) {
            paired = true
            if (nowPlaying == null && queue.isNotEmpty()) {
                try { advance() } catch (e: LoungeException) { }
            }
        }
    }
}
