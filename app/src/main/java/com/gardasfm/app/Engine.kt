package com.gardasfm.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

data class Item(val artist: String, val title: String, val url: String, val dur: Int, val thumb: String = "") {
    fun toJson(): JSONObject = JSONObject().put("a", artist).put("t", title).put("u", url).put("d", dur).put("i", thumb)
    companion object {
        fun from(o: JSONObject) = Item(o.optString("a"), o.optString("t"), o.optString("u"), o.optInt("d"), o.optString("i"))
    }
}

data class Snap(val pos: Double = 0.0, val dur: Double = 0.0, val playing: Boolean = false)

/** Bildirimdeki ileri/geri tuslari bizim sirayi yonetsin diye. */
class NavPlayer(p: Player) : ForwardingPlayer(p) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS).build()
    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
    override fun hasNextMediaItem() = true
    override fun hasPreviousMediaItem() = true
    override fun seekToNext() { Engine.next() }
    override fun seekToPrevious() { Engine.prev() }
    override fun seekToNextMediaItem() { Engine.next() }
    override fun seekToPreviousMediaItem() { Engine.prev() }
}

/** Uygulamanin beyni: oynatici + sira + favoriler + yerel sunucu (PC 9.0 arayuzunun konustugu API). */
object Engine {
    const val PORT = 53690
    const val MIX = "Favorilerim"
    const val RECENT = "Son çalınanlar"
    const val QUEUE = "Sıradakiler"
    val token: String = UUID.randomUUID().toString().replace("-", "")
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newCachedThreadPool()
    val toasts = ConcurrentLinkedQueue<String>()
    lateinit var app: Context
    lateinit var player: ExoPlayer
    lateinit var session: MediaSession
    @Volatile var started = false
    @Volatile var snap = Snap()
    @Volatile var queue: List<Item> = emptyList()
    @Volatile var idx = -1
    @Volatile var resolving = false
    @Volatile var lastResults: List<Item> = emptyList()
    @Volatile var cover: ByteArray? = null
    @Volatile var cid = 0
    @Volatile var shuffle = false
    @Volatile var repeat = 0          // 0 kapali, 1 hepsi, 2 tek sarki
    @Volatile var vol = 70
    val favs = mutableListOf<Item>()
    val recent = mutableListOf<Item>()
    val settings = JSONObject()
    private var gen = 0
    private var fails = 0

    @Synchronized
    fun start(ctx: Context) { // ana is parcacigindan cagrilmali
        if (started) return
        app = ctx.applicationContext
        Extractor.init()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app).setDataSourceFactory(http))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) onEnded() }
            override fun onPlayerError(error: PlaybackException) { toasts.add("Çalma hatası, sıradakine geçiliyor"); failSkip() }
        })
        session = MediaSession.Builder(app, NavPlayer(player)).build()
        load()
        tick()
        GfmServer().start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        started = true
    }

    private fun tick() {
        snap = Snap(player.currentPosition / 1000.0, if (player.duration > 0) player.duration / 1000.0 else 0.0,
            resolving || player.isPlaying || (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady))
        main.postDelayed({ tick() }, 300)
    }

    fun cur(): Item? = queue.getOrNull(idx)

    fun startPlay(list: List<Item>, i: Int) { if (list.isEmpty()) return; queue = list.toList(); idx = i.coerceIn(0, list.size - 1); playCurrent() }

    fun playCurrent() {
        val song = queue.getOrNull(idx) ?: return
        val g = ++gen
        resolving = true
        io.execute {
            try {
                val url = Extractor.streamUrl(song.url)
                val cov = Extractor.cover(song)
                if (g != gen) return@execute
                main.post {
                    if (g != gen) return@post
                    cover = cov
                    cid = if (cov != null) (song.url.hashCode() and 0x7fffffff) % 1000000007 + 1 else 0
                    player.setMediaItem(MediaItem.Builder().setUri(url)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build()).build())
                    player.prepare(); player.play()
                    resolving = false; fails = 0
                }
                addRecent(song)
            } catch (e: Exception) {
                if (g != gen) return@execute
                resolving = false
                toasts.add("Açılamadı: ${song.title}")
                failSkip()
            }
        }
    }

    private fun failSkip() { if (++fails <= 3) main.postDelayed({ next(true) }, 600) else fails = 0 }

    private fun onEnded() { if (repeat == 2) playCurrent() else next(true) }

    fun next(auto: Boolean = false) {
        main.post {
            if (queue.isEmpty()) return@post
            var n = if (shuffle && queue.size > 1) (queue.indices - idx).random() else idx + 1
            if (n >= queue.size) {
                if (repeat == 1 || !auto) n = 0 else { player.pause(); return@post }
            }
            idx = n; playCurrent()
        }
    }

    fun prev() {
        main.post {
            if (queue.isEmpty()) return@post
            if (player.currentPosition > 3000) { player.seekTo(0); return@post }
            idx = if (idx <= 0) queue.size - 1 else idx - 1
            playCurrent()
        }
    }

    fun toggle() {
        main.post {
            if (player.isPlaying) player.pause()
            else if (player.mediaItemCount > 0) player.play()
            else if (queue.isNotEmpty()) playCurrent()
        }
    }

    fun seek(f: Double) { main.post { if (player.duration > 0) player.seekTo((player.duration * f).toLong()) } }
    
    // Çakışmayı önlemek için fonksiyon adı setVolume olarak değiştirildi:
    fun setVolume(v: Int) { vol = v.coerceIn(0, 100); main.post { player.volume = vol / 100f } }

    fun isFav(it: Item) = favs.any { f -> f.url == it.url }
    fun toggleFav(it: Item) { if (isFav(it)) favs.removeAll { f -> f.url == it.url } else favs.add(0, it); save() }
    private fun addRecent(it: Item) = synchronized(recent) { recent.removeAll { r -> r.url == it.url }; recent.add(0, it); if (recent.size > 100) recent.removeAt(100); save() }

    fun itemsOf(n: String): List<Item> = when (n) {
        "__ara" -> lastResults
        MIX -> favs.toList()
        RECENT -> synchronized(recent) { recent.toList() }
        QUEUE -> queue
        else -> emptyList()
    }

    fun search(q: String): List<Item> {
        if (q.trim().length < 2) return emptyList()
        val local = (favs + recent).filter { s -> q.lowercase().split(" ").all { w -> "${s.artist} ${s.title}".lowercase().contains(w) } }
        val res = (local + try { Extractor.search(q) } catch (e: Exception) { toasts.add("Arama başarısız"); emptyList() }).distinctBy { it.url }
        lastResults = res
        return res
    }

    private val moods = mapOf(
        "Sakin" to "sakin duygusal türkçe şarkılar", "Hareketli" to "hareketli enerjik pop şarkılar",
        "Gece sürüşü" to "gece sürüşü rap elektronik şarkılar", "Çalışma" to "çalışırken dinlenecek lofi akustik")

    fun homeAction(id: String) {
        io.execute {
            try {
                val res: List<Item> = when {
                    id == "shuffle" -> { val pool = (favs + recent).distinctBy { it.url }; (if (pool.size >= 5) pool else Extractor.search("türkçe pop hit şarkılar")).shuffled() }
                    id == "daily" -> Extractor.search("yeni çıkan türkçe şarkılar").shuffled()
                    id == "radio" -> { val c = cur(); if (c == null) { toasts.add("Önce bir şarkı çal"); emptyList() } else Extractor.search("${c.artist} ${c.title} benzeri şarkılar").filter { it.url != c.url } }
                    id.startsWith("mood:") -> Extractor.search(moods[id.substring(5)] ?: id.substring(5)).shuffled()
                    else -> emptyList()
                }
                if (res.isNotEmpty()) main.post { startPlay(res, 0) }
            } catch (e: Exception) { toasts.add("Liste açılamadı") }
        }
    }

    fun cmd(o: JSONObject) {
        when (o.optString("cmd")) {
            "toggle" -> toggle()
            "next" -> next()
            "prev" -> prev()
            "seek" -> seek(o.optDouble("f", 0.0))
            "vol" -> setVolume(o.optInt("v", 70))
            "home" -> homeAction(o.optString("id"))
            "shuffle" -> shuffle = !shuffle
            "repeat" -> repeat = (repeat + 1) % 3
            "play_list" -> startPlay(itemsOf(o.optString("n")), o.optInt("i"))
            "play_queue" -> startPlay(queue, o.optInt("i"))
            "fav_row" -> itemsOf(o.optString("n")).getOrNull(o.optInt("i"))?.let { toggleFav(it) }
            "fav_now" -> cur()?.let { toggleFav(it) }
            "enqueue" -> itemsOf(o.optString("n")).getOrNull(o.optInt("i"))?.let { queue = queue + it }
            "set" -> { settings.put(o.optString("k"), o.opt("v")); save() }
            "dl_row", "dl_list", "sleep", "eq", "rate" -> toasts.add("Bu özellik sonraki sürümde geliyor")
        }
    }

    private fun prefs() = app.getSharedPreferences("gfm", Context.MODE_PRIVATE)
    private fun enc(l: List<Item>) = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()
    private fun dec(s: String?): List<Item> = try { val a = JSONArray(s ?: "[]"); (0 until a.length()).map { Item.from(a.getJSONObject(it)) } } catch (e: Exception) { emptyList() }
    @Synchronized fun save() { prefs().edit().putString("favs", enc(favs)).putString("recent", enc(synchronized(recent) { recent.toList() })).putString("settings", settings.toString()).apply() }
    private fun load() {
        favs.addAll(dec(prefs().getString("favs", null))); recent.addAll(dec(prefs().getString("recent", null)))
        try { val s = JSONObject(prefs().getString("settings", "{}")!!); s.keys().forEach { settings.put(it, s.get(it)) } } catch (e: Exception) {}
    }
}
