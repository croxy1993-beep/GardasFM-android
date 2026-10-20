package com.gardas.fm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.audiofx.Equalizer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PL_PREFIX = "pl:"
const val LOCAL_PL = "Yerel müzik"

/** Ekranda kısa mesaj (Toast) göstermek için. */
data class Notice(val id: Int, val text: String)

/**
 * Uygulamanın tek çalma motoru. Arayüz (Compose) ve bildirim servisi aynı oynatıcıyı kullanır.
 */
object Engine {
    lateinit var app: Application
    lateinit var player: ExoPlayer
    lateinit var sessionPlayer: Player
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val queue = MutableStateFlow<List<Song>>(emptyList())
    val index = MutableStateFlow(-1)
    val playing = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val message = MutableStateFlow("Gardaş FM hazır")
    val coverId = MutableStateFlow<String?>(null)
    val lyrics = MutableStateFlow(Lyrics())
    val shuffle = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(0)            // 0 kapalı, 1 liste, 2 tek şarkı
    val favorites = MutableStateFlow<List<Song>>(emptyList())
    val recent = MutableStateFlow<List<Song>>(emptyList())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val sleepMin = MutableStateFlow(0)
    val speed = MutableStateFlow(1f)
    val eqPreset = MutableStateFlow(0)
    val notice = MutableStateFlow(Notice(0, ""))
    val restored = MutableStateFlow(false)          // son oturum yüklendi ama henüz çalmıyor
    val tagged = MutableStateFlow<List<Pair<Song, List<String>>>>(emptyList())   // etiketli şarkılar

    val eqNames = listOf("Düz", "Bas", "Vokal", "Tiz", "Parti")
    private val eqShapes = listOf(
        floatArrayOf(),
        floatArrayOf(0.9f, 0.6f, 0f, -0.1f, -0.1f),
        floatArrayOf(-0.3f, 0f, 0.6f, 0.5f, 0f),
        floatArrayOf(-0.2f, -0.1f, 0f, 0.6f, 0.9f),
        floatArrayOf(0.7f, 0.2f, -0.1f, 0.4f, 0.7f),
    )

    private var token = 0
    private var failStreak = 0
    private var retried = false
    private var resolveJob: Job? = null
    private var sleepJob: Job? = null
    private var serviceStarted = false
    private var pendingSeek = 0L
    private var noticeId = 0
    private var eq: Equalizer? = null
    private val backStack = ArrayDeque<Song>()
    private val cache = HashMap<String, Pair<Resolved, Long>>()

    fun say(text: String) {
        notice.value = Notice(++noticeId, text)
    }

    fun init(application: Application) {
        app = application
        Downloads.init(app)
        shuffle.value = Store.getBool("shuffle", false)
        repeatMode.value = Store.getInt("repeat_mode", 0)
        speed.value = Store.getFloat("speed", 1f)
        eqPreset.value = Store.getInt("eq", 0)
        favorites.value = Store.getSongs("fav")
        recent.value = Store.getSongs("recent")
        playlists.value = Store.getPlaylists()
        tagged.value = loadTags()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(DownloaderImpl.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.setPlaybackSpeed(speed.value)
        sessionPlayer = QueuePlayer(player)

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
                if (isPlaying) {
                    failStreak = 0
                    retried = false
                    loading.value = false
                } else {
                    saveSession()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING && player.playWhenReady) loading.value = true
                if (state == Player.STATE_READY) loading.value = false
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyEq(audioSessionId)
            }

            override fun onPlayerError(error: PlaybackException) {
                val song = queue.value.getOrNull(index.value) ?: return
                if (!retried && !song.isLocal && Downloads.fileFor(song) == null) {   // link süresi dolmuş olabilir
                    retried = true
                    cache.remove(song.key)
                    playIndex(index.value, push = false)
                } else {
                    onFail(error)
                }
            }
        })

        // kaldığın yerden devam için son oturum
        val lq = Store.getSongs("last_queue")
        if (lq.isNotEmpty()) {
            queue.value = lq
            index.value = Store.getInt("last_index", 0).coerceIn(0, lq.lastIndex)
            restored.value = true
        }
    }

    // ------------------------------------------------------------ çalma
    fun playList(songs: List<Song>, start: Int) {
        if (songs.isEmpty()) return
        queue.value = songs
        playIndex(start.coerceIn(0, songs.lastIndex))
    }

    fun playShuffled(songs: List<Song>) {
        if (songs.isEmpty()) return
        shuffle.value = true
        Store.putBool("shuffle", true)
        playList(songs, songs.indices.random())
    }

    fun playAllShuffled() = playShuffled(CATALOG.values.flatten())

    fun resumeSession() {
        if (queue.value.isEmpty()) return
        pendingSeek = Store.getStr("last_pos", "0").toLongOrNull() ?: 0L
        playIndex(index.value.coerceAtLeast(0), push = false)
    }

    private fun saveSession() {
        try {
            val q = queue.value
            if (q.isEmpty()) return
            Store.putSongs("last_queue", q.take(150))
            Store.putInt("last_index", index.value.coerceAtLeast(0))
            Store.putStr("last_pos", player.currentPosition.toString())
        } catch (e: Exception) {
            // oturum kaydedilemezse önemli değil
        }
    }

    fun playIndex(i: Int, push: Boolean = true) {
        val q = queue.value
        if (i !in q.indices) return
        val song = q[i]
        if (push) {
            val cur = q.getOrNull(index.value)
            if (cur != null && cur.key != song.key && player.mediaItemCount > 0) {
                backStack.addLast(cur)
                if (backStack.size > 50) backStack.removeFirst()
            }
        }
        index.value = i
        restored.value = false
        val my = ++token
        resolveJob?.cancel()
        loading.value = true
        message.value = "Bağlanıyor..."
        coverId.value = null
        lyrics.value = Lyrics(status = "Sözler aranıyor...")
        player.stop()
        resolveJob = scope.launch {
            try {
                val file = Downloads.fileFor(song)
                val uri: Uri
                var vid: String?
                when {
                    file != null -> {
                        uri = Uri.fromFile(file)
                        vid = Downloads.videoIdFor(song)
                    }
                    song.isLocal -> {
                        uri = Uri.parse(song.url ?: "")
                        vid = null
                    }
                    else -> {
                        val r = cachedOrResolve(song)
                        uri = Uri.parse(r.streamUrl)
                        vid = r.videoId
                    }
                }
                if (my != token) return@launch
                coverId.value = vid
                val meta = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist.ifBlank { "Gardaş FM" })
                    .apply { vid?.let { setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$it/hqdefault.jpg")) } }
                    .build()
                player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(meta).build())
                player.prepare()
                val seek = pendingSeek
                pendingSeek = 0L
                if (seek > 3000) player.seekTo(seek)
                player.play()
                message.value = if (file != null) "Çalıyor (indirilmiş)" else "Çalıyor"
                remember(song)
                ensureService()
                saveSession()
                launch {
                    val l = LyricsApi.fetch(song)
                    if (my == token) lyrics.value = l
                }
                prefetchNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (my == token) onFail(e)
            }
        }
    }

    private suspend fun cachedOrResolve(song: Song): Resolved {
        val hit = cache[song.key]
        if (hit != null && System.currentTimeMillis() - hit.second < 4 * 3600_000L) return hit.first
        val r = Extractor.resolve(song)
        cache[song.key] = r to System.currentTimeMillis()
        if (cache.size > 100) cache.keys.take(40).forEach { cache.remove(it) }
        return r
    }

    private fun prefetchNext() {
        val q = queue.value
        if (q.size < 2 || !isOnline()) return
        val nxt = q[(index.value + 1) % q.size]
        if (cache.containsKey(nxt.key) || nxt.isLocal || Downloads.has(nxt)) return
        scope.launch {
            try {
                cachedOrResolve(nxt)
            } catch (e: Exception) {
                // önceden hazırlama başarısız olursa sıra gelince tekrar denenir
            }
        }
    }

    private fun onFail(e: Throwable) {
        loading.value = false
        failStreak++
        message.value = "Çalınamadı: ${e.message?.take(70) ?: "bilinmeyen hata"}"
        val my = token
        if (failStreak < 5 && queue.value.size > 1) {
            scope.launch {
                delay(1500)
                if (my == token) next()
            }
        }
    }

    private fun onEnded() {
        val q = queue.value
        when (repeatMode.value) {
            2 -> playIndex(index.value, push = false)
            1 -> next()
            else -> if (shuffle.value || index.value < q.lastIndex) next() else message.value = "Liste bitti"
        }
    }

    private fun remember(song: Song) {
        val list = recent.value.filter { it.key != song.key }.toMutableList()
        list.add(0, song)
        recent.value = list.take(40)
        Store.putSongs("recent", recent.value)
        Store.addPlay(song)
    }

    private fun ensureService() {
        if (serviceStarted) return
        try {
            app.startService(Intent(app, PlayerService::class.java))
            serviceStarted = true
        } catch (e: Exception) {
            // uygulama arka plandaysa başlatılamaz; çalma yine de devam eder
        }
    }

    fun isOnline(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------ kumanda
    fun next() {
        val q = queue.value
        if (q.isEmpty()) return
        val cur = index.value
        var n = if (shuffle.value && q.size > 1) {
            q.indices.filter { it != cur }.random()
        } else {
            (cur + 1) % q.size
        }
        if (!isOnline()) {      // internet yoksa sadece indirilmiş/yerel şarkılara geç
            val order = (1..q.size).map { (cur + it) % q.size }
            val offline = order.filter { Downloads.has(q[it]) || q[it].isLocal }
            n = (if (shuffle.value && offline.size > 1) offline.random() else offline.firstOrNull()) ?: run {
                message.value = "İnternet yok: sırada indirilmiş şarkı yok"
                return
            }
        }
        playIndex(n)
    }

    fun prev() {
        if (player.currentPosition > 4000) {
            player.seekTo(0)
            return
        }
        val q = queue.value
        if (q.isEmpty()) return
        while (backStack.isNotEmpty()) {          // geri tuşu: gerçekten çalınan önceki şarkıya dön
            val s = backStack.removeLast()
            val at = q.indexOfFirst { it.key == s.key }
            if (at >= 0) {
                playIndex(at, push = false)
                return
            }
        }
        playIndex((index.value - 1 + q.size) % q.size, push = false)
    }

    fun togglePlay() {
        when {
            player.isPlaying -> player.pause()
            player.mediaItemCount == 0 && queue.value.isNotEmpty() -> {
                if (restored.value) resumeSession() else playIndex(index.value.coerceAtLeast(0))
            }
            else -> player.play()
        }
    }

    fun seekFraction(f: Float) {
        val d = player.duration
        if (d > 0) player.seekTo((d * f).toLong())
    }

    fun toggleShuffle() {
        shuffle.value = !shuffle.value
        Store.putBool("shuffle", shuffle.value)
    }

    fun cycleRepeat() {
        repeatMode.value = (repeatMode.value + 1) % 3
        Store.putInt("repeat_mode", repeatMode.value)
    }

    fun cycleSpeed() {
        val opts = listOf(1f, 1.25f, 1.5f, 0.75f)
        val nxt = opts[(opts.indexOf(speed.value).coerceAtLeast(0) + 1) % opts.size]
        speed.value = nxt
        player.setPlaybackSpeed(nxt)
        Store.putFloat("speed", nxt)
    }

    fun cycleEq() {
        eqPreset.value = (eqPreset.value + 1) % eqNames.size
        Store.putInt("eq", eqPreset.value)
        applyEq(player.audioSessionId)
    }

    private fun applyEq(sessionId: Int) {
        try {
            eq?.release()
            eq = null
            val shape = eqShapes[eqPreset.value]
            if (shape.isEmpty() || sessionId == C.AUDIO_SESSION_ID_UNSET) return
            val e = Equalizer(0, sessionId)
            e.enabled = true
            val bands = e.numberOfBands.toInt()
            val min = e.bandLevelRange[0].toInt()
            val max = e.bandLevelRange[1].toInt()
            for (b in 0 until bands) {
                val pos = if (bands <= 1) 0f else b.toFloat() / (bands - 1) * (shape.size - 1)
                val lo = pos.toInt().coerceIn(0, shape.size - 1)
                val hi = (lo + 1).coerceAtMost(shape.size - 1)
                val v = shape[lo] + (shape[hi] - shape[lo]) * (pos - lo)
                val level = if (v >= 0) v * max else -v * min
                e.setBandLevel(b.toShort(), level.toInt().coerceIn(min, max).toShort())
            }
            eq = e
        } catch (ex: Exception) {
            // cihaz ekolayzırı desteklemiyorsa sessizce geç
        }
    }

    /** Uyku zamanlayıcısı: kapalı -> 15 -> 30 -> 60 dk -> kapalı */
    fun cycleSleep() {
        val nxt = when (sleepMin.value) {
            0 -> 15
            15 -> 30
            30 -> 60
            else -> 0
        }
        sleepMin.value = nxt
        sleepJob?.cancel()
        if (nxt > 0) {
            sleepJob = scope.launch {
                delay(nxt * 60_000L)
                player.pause()
                sleepMin.value = 0
            }
        }
    }

    // ------------------------------------------------------------ kuyruk
    fun addToQueue(song: Song) {
        if (queue.value.isEmpty()) {
            playList(listOf(song), 0)
            return
        }
        queue.value = queue.value + song
        say("Sıraya eklendi")
    }

    fun playNext(song: Song) {
        if (queue.value.isEmpty()) {
            playList(listOf(song), 0)
            return
        }
        val list = queue.value.toMutableList()
        list.add((index.value + 1).coerceIn(0, list.size), song)
        queue.value = list
        say("Sıradaki olarak eklendi")
    }

    fun removeFromQueue(i: Int) {
        val list = queue.value.toMutableList()
        if (i !in list.indices) return
        if (i == index.value) {
            say("Çalan şarkı kuyruktan çıkarılamaz")
            return
        }
        list.removeAt(i)
        if (i < index.value) index.value = index.value - 1
        queue.value = list
    }

    fun moveInQueue(i: Int, delta: Int) {
        val list = queue.value.toMutableList()
        val j = i + delta
        if (i !in list.indices || j !in list.indices) return
        val cur = index.value
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        index.value = when (cur) {
            i -> j
            j -> i
            else -> cur
        }
        queue.value = list
    }

    // ------------------------------------------------------------ favoriler ve çalma listeleri
    fun toggleFavorite(song: Song) {
        val list = favorites.value.toMutableList()
        val at = list.indexOfFirst { it.key == song.key }
        if (at >= 0) list.removeAt(at) else list.add(song)
        favorites.value = list
        Store.putSongs("fav", list)
    }

    private fun savePlaylists() = Store.putPlaylists(playlists.value)

    fun createPlaylist(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || playlists.value.any { it.name.equals(n, ignoreCase = true) }) return false
        playlists.value = playlists.value + Playlist(n, emptyList())
        savePlaylists()
        return true
    }

    fun deletePlaylist(name: String) {
        playlists.value = playlists.value.filter { it.name != name }
        savePlaylists()
    }

    fun addToPlaylist(name: String, song: Song) {
        playlists.value = playlists.value.map {
            if (it.name == name && it.songs.none { s -> s.key == song.key }) it.copy(songs = it.songs + song) else it
        }
        savePlaylists()
        say("Listeye eklendi: $name")
    }

    fun removeFromPlaylist(name: String, song: Song) {
        playlists.value = playlists.value.map {
            if (it.name == name) it.copy(songs = it.songs.filter { s -> s.key != song.key }) else it
        }
        savePlaylists()
    }

    private fun setPlaylistSongs(name: String, songs: List<Song>) {
        playlists.value = if (playlists.value.any { it.name == name }) {
            playlists.value.map { if (it.name == name) it.copy(songs = songs) else it }
        } else {
            playlists.value + Playlist(name, songs)
        }
        savePlaylists()
    }

    /** YouTube çalma listesi bağlantısından yeni bir liste oluşturur. */
    fun importYoutubePlaylist(url: String) {
        say("Liste alınıyor...")
        scope.launch {
            try {
                val (name, songs) = Extractor.playlist(url.trim())
                if (songs.isEmpty()) {
                    say("Listede şarkı bulunamadı")
                    return@launch
                }
                var final = name
                var n = 2
                while (playlists.value.any { it.name.equals(final, ignoreCase = true) }) final = "$name ($n)".also { n++ }
                setPlaylistSongs(final, songs)
                say("${songs.size} şarkı eklendi: $final")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Liste alınamadı: ${e.message?.take(50) ?: "hata"}")
            }
        }
    }

    /** Telefondaki bir klasörden müzik dosyalarını "Yerel müzik" listesine ekler. */
    fun importFolder(ctx: Context, uri: Uri) {
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        // kalıcı izin alınamazsa bu oturumda yine çalışır
                    }
                    val root = DocumentFile.fromTreeUri(ctx, uri)
                    val out = ArrayList<Song>()
                    val exts = setOf("mp3", "m4a", "flac", "ogg", "wav", "aac", "opus")
                    fun walk(dir: DocumentFile, depth: Int) {
                        for (f in dir.listFiles()) {
                            if (f.isDirectory) {
                                if (depth < 3) walk(f, depth + 1)
                            } else if (f.isFile) {
                                val n = f.name ?: continue
                                val ext = n.substringAfterLast('.', "").lowercase()
                                if (f.type?.startsWith("audio") == true || ext in exts) {
                                    val base = n.substringBeforeLast('.')
                                    out.add(
                                        if (" - " in base) {
                                            Song(base.substringBefore(" - ").trim(), base.substringAfter(" - ").trim(), f.uri.toString())
                                        } else {
                                            Song("Yerel", base.trim(), f.uri.toString())
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (root != null) walk(root, 0)
                    out
                }
                setPlaylistSongs(LOCAL_PL, found)
                say("${found.size} şarkı eklendi: $LOCAL_PL")
            } catch (e: Exception) {
                say("Klasör okunamadı: ${e.message?.take(50) ?: "hata"}")
            }
        }
    }

    // ------------------------------------------------------------ etiketler (5.0)
    private fun loadTags(): List<Pair<Song, List<String>>> = try {
        val a = org.json.JSONArray(Store.getStr("tags", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val t = o.getJSONArray("tags")
            songsFromJson(org.json.JSONArray().put(o.getJSONObject("song"))).first() to (0 until t.length()).map { i -> t.getString(i) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveTags() {
        val a = org.json.JSONArray()
        tagged.value.forEach { (song, tags) ->
            val t = org.json.JSONArray()
            tags.forEach { t.put(it) }
            a.put(org.json.JSONObject().put("song", songsToJson(listOf(song)).getJSONObject(0)).put("tags", t))
        }
        Store.putStr("tags", a.toString())
    }

    fun tagsFor(song: Song): List<String> = tagged.value.firstOrNull { it.first.key == song.key }?.second ?: emptyList()

    fun setTags(song: Song, tags: List<String>) {
        val clean = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val rest = tagged.value.filter { it.first.key != song.key }
        tagged.value = if (clean.isEmpty()) rest else rest + (song to clean)
        saveTags()
        say(if (clean.isEmpty()) "Etiketler silindi" else "Etiketler kaydedildi")
    }

    /** Kullanılan etiketler, en çok kullanılandan başlayarak. */
    fun allTags(): List<Pair<String, Int>> =
        tagged.value.flatMap { it.second }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }

    fun songsWithTag(tag: String): List<Song> = tagged.value.filter { tag in it.second }.map { it.first }

    // ------------------------------------------------------------ karışıklar (5.0)
    /** Ruh haline göre mix: (ad, alt yazı, katalog kategorileri, etiket) */
    val moods = listOf(
        listOf("Sakin", "Yavaş, duygusal, çay yanında", "Arabesk Damar,Popüler Pop", "sakin"),
        listOf("Hareketli", "Tempo yüksek, enerji tam", "Popüler Pop,Yabancı Rap", "hareketli"),
        listOf("Gece sürüşü", "Cam açık, yol boş", "Türkçe Rap,Yabancı Rap,Rock Alternatif", "gece"),
        listOf("Çalışma", "Dikkat dağıtmayan akış", "Popüler Pop,Rock Alternatif", "çalışma"),
    )

    private fun playable(s: Song): Boolean = isOnline() || s.isLocal || Downloads.has(s)

    fun playMood(name: String) {
        val m = moods.firstOrNull { it[0] == name } ?: return
        val pool = LinkedHashMap<String, Song>()
        val cats = m[2].split(",").filter { CATALOG.containsKey(it) }.ifEmpty { CATALOG.keys.toList() }
        for (c in cats) CATALOG[c]?.forEach { if (playable(it)) pool.putIfAbsent(it.key, it) }
        songsWithTag(m[3]).forEach { if (playable(it)) pool.putIfAbsent(it.key, it) }
        if (pool.isEmpty()) {
            say("Bu karışık için şarkı yok (internet yoksa önce şarkı indir)")
            return
        }
        shuffle.value = false
        Store.putBool("shuffle", false)
        val items = pool.values.shuffled().take(25)
        playList(items, 0)
        say("$name karışığı hazır: ${items.size} şarkı")
    }

    /** Bugünün karışığı: favoriler + son çalınanlar + katalogdan, her gün farklı ama gün içinde sabit. */
    fun todaysMix(): List<Song> {
        val c = java.util.Calendar.getInstance()
        val seed = c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR)
        val rnd = java.util.Random(seed)
        val pool = LinkedHashMap<String, Song>()
        (favorites.value + recent.value).forEach { pool.putIfAbsent(it.key, it) }
        CATALOG.values.flatten().shuffled(rnd).take(40).forEach { pool.putIfAbsent(it.key, it) }
        return pool.values.toList().shuffled(rnd).take(30)
    }
}

/** Bildirimdeki ve kulaklıktaki "sonraki/önceki" tuşlarını kendi kuyruğumuza yönlendirir. */
private class QueuePlayer(p: Player) : ForwardingPlayer(p) {
    private val extra = listOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands {
        val b = super.getAvailableCommands().buildUpon()
        extra.forEach { b.add(it) }
        return b.build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        command in extra || super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true
    override fun seekToNext() = Engine.next()
    override fun seekToNextMediaItem() = Engine.next()
    override fun seekToPrevious() = Engine.prev()
    override fun seekToPreviousMediaItem() = Engine.prev()
}
