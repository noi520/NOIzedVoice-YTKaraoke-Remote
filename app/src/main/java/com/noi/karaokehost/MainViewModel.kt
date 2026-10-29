package com.noi.karaokehost

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var results by mutableStateOf<List<Song>>(emptyList())
    var searching by mutableStateOf(false)
    var qrBitmap by mutableStateOf<Bitmap?>(null)
    var qrUrl by mutableStateOf("")

    val paired: Boolean get() = Engine.paired
    val tvConnected: Boolean get() = Engine.tvConnected
    val nowPlaying: Song? get() = Engine.nowPlaying
    val playback: String get() = Engine.playback
    val locked: Boolean get() = Engine.locked
    val adPlaying: Boolean get() = Engine.adPlaying
    val adSkippable: Boolean get() = Engine.adSkippable
    val guests: Int get() = Engine.guests
    val queue: List<Song> get() = Engine.queue
    var message: String?
        get() = Engine.message
        set(v) { Engine.message = v }

    private fun act(okMsg: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (okMsg != null) Engine.message = okMsg
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Engine.message = e.message ?: "Something went wrong"
            }
        }
    }

    fun skip() = act { Engine.skip() }
    fun togglePause() = act { if (Engine.playback == "paused") Engine.resume() else Engine.pause() }
    fun clearQueue() = act("Queue cleared") { Engine.clear() }
    fun toggleLock() = act { Engine.locked = !Engine.locked }
    fun skipAd() = act { Engine.skipAd() }
    fun remove(s: Song) = act { Engine.remove(s.videoId) }
    fun playNext(s: Song) = act("Playing next") { Engine.playNext(s.videoId) }
    fun move(s: Song, newPos: Int) = act { Engine.reorder(s.videoId, newPos) }
    fun add(s: Song) = act("Added: ${s.title}") { Engine.add(s.copy(addedBy = "host"), true) }
    fun pair(code: String) = act("Paired with the TV!") { Engine.pair(code) }
    fun unpair() = act("Disconnected from the TV") { Engine.unpair() }

    fun search(q: String) {
        if (q.trim().length < 2) { Engine.message = "Type at least 2 characters"; return }
        searching = true
        viewModelScope.launch {
            try {
                results = YtSearch.search(q.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Engine.message = "Search failed: ${e.message}"
            }
            searching = false
        }
    }

    fun loadQr() {
        val url = Engine.guestUrl()
        val m = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512)
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        for (x in 0 until 512) for (y in 0 until 512) bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        qrBitmap = bmp
        qrUrl = url
    }
}
