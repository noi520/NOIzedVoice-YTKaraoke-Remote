package com.noi.karaokehost

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Guest web server sa phone: QR -> /guest?room=TOKEN. Polling ang update (walang websocket). */
class GuestServer(private val ctx: Context) : NanoHTTPD(Engine.PORT) {

    private class St(val code: Int, val text: String) : Response.IStatus {
        override fun getDescription(): String = "$code $text"
        override fun getRequestStatus(): Int = code
    }

    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun rate(bucket: String, ip: String, limit: Int) {
        val dq = hits.getOrPut("$bucket|$ip") { ArrayDeque() }
        val now = System.currentTimeMillis()
        synchronized(dq) {
            while (dq.isNotEmpty() && now - dq.first() > 60_000) dq.removeFirst()
            if (dq.size >= limit) throw EngineError(429, "Too many requests. Try again later.")
            dq.addLast(now)
        }
    }

    private fun json(code: Int, o: Any): Response =
        NanoHTTPD.newFixedLengthResponse(St(code, "x"), "application/json; charset=utf-8", o.toString())

    private fun text(code: Int, t: String): Response =
        NanoHTTPD.newFixedLengthResponse(St(code, "x"), "text/plain; charset=utf-8", t)

    private fun asset(path: String, mime: String): Response = try {
        val bytes = ctx.assets.open(path).use { it.readBytes() }
        NanoHTTPD.newFixedLengthResponse(St(200, "OK"), mime, bytes.inputStream(), bytes.size.toLong())
            .also { it.addHeader("Cache-Control", "no-store") }
    } catch (e: Exception) {
        text(404, "Not found")
    }

    private fun roomOk(s: IHTTPSession): Boolean {
        val given = s.parameters["room"]?.firstOrNull() ?: s.headers["x-room-code"] ?: return false
        return MessageDigest.isEqual(given.toByteArray(), Engine.roomToken.toByteArray())
    }

    private fun songJson(s: Song, pos: Int? = null) = JSONObject()
        .put("videoId", s.videoId).put("title", s.title).put("thumbnail", s.thumbnail)
        .put("duration", s.duration).put("addedBy", s.addedBy).also { if (pos != null) it.put("position", pos) }

    private fun queueJson() = JSONArray(Engine.queue.mapIndexed { i, s -> songJson(s, i + 1) })

    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: LoungeException) {
        throw EngineError(502, e.message ?: "Cannot reach the TV")
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val ip = session.remoteIpAddress ?: "?"
        return try {
            when {
                path == "/guest" ->
                    if (roomOk(session)) asset("guest/index.html", "text/html; charset=utf-8")
                    else text(403, "Scan the host's QR code to join.")
                path == "/static/guest/app.js" -> asset("guest/app.js", "text/javascript; charset=utf-8")
                path == "/static/guest/style.css" -> asset("guest/style.css", "text/css; charset=utf-8")
                path.startsWith("/api/") -> {
                    if (!roomOk(session)) throw EngineError(403, "Scan the host's QR code to join.")
                    api(session, path, ip)
                }
                else -> text(404, "Not found")
            }
        } catch (e: EngineError) {
            json(e.code, JSONObject().put("detail", e.message))
        } catch (e: Exception) {
            json(500, JSONObject().put("detail", "Server error: ${e.message}"))
        }
    }

    private fun api(s: IHTTPSession, path: String, ip: String): Response {
        Engine.touchGuest(ip)
        val post = s.method == Method.POST
        var body = ""
        if (post) {
            val files = HashMap<String, String>()
            s.parseBody(files)
            body = files["postData"].orEmpty()
        }
        return when {
            path == "/api/state" && !post -> json(200, JSONObject()
                .put("nowPlaying", Engine.nowPlaying?.let { songJson(it) } ?: JSONObject.NULL)
                .put("locked", Engine.locked).put("adPlaying", Engine.adPlaying)
                .put("adSkippable", Engine.adSkippable).put("tv", Engine.tvConnected)
                .put("queue", queueJson()))
            path == "/api/search" && !post -> {
                val q = (s.parameters["q"]?.firstOrNull() ?: "").trim()
                if (q.length !in 2..100) throw EngineError(422, "Search must be 2 to 100 characters")
                rate("search", ip, 10)
                val r = try { runBlocking { YtSearch.search(q) } }
                catch (e: Exception) { throw EngineError(502, "Search failed: ${e.message}") }
                json(200, JSONArray(r.map { songJson(it) }))
            }
            path == "/api/queue/add" && post -> {
                val o = try { JSONObject(body) } catch (e: Exception) { throw EngineError(422, "Invalid request") }
                val id = o.optString("videoId")
                if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(id)) throw EngineError(422, "Invalid videoId")
                rate("add", ip, 5)
                val song = YtSearch.lookup(id) ?: throw EngineError(404, "Search for the song again")
                blocking { Engine.add(song.copy(addedBy = o.optString("guestId", "guest").take(64)), false) }
                json(200, JSONObject().put("ok", true).put("queue", queueJson()))
            }
            path == "/api/ad/skip" && post -> {
                rate("adskip", ip, 5)
                blocking { Engine.skipAd() }
                json(200, JSONObject().put("ok", true))
            }
            else -> throw EngineError(404, "Not found")
        }
    }
}
