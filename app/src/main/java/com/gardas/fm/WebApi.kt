package com.gardas.fm

import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.C
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Telefon arayüzünün (ortak web arayüzü) konuştuğu küçük API.
 * PC sürümündeki /api/... uçlarıyla aynı biçimde yanıt verir, böylece arayüz iki yerde de aynıdır.
 */
object WebApi {
    private const val QUEUE = "Sıradakiler"
    private const val RESULTS = "__ara"

    interface Hooks {
        fun pickBackground()
        fun pickFolder()
        fun quit()
    }

    var hooks: Hooks? = null
    private var results: List<Song> = emptyList()
    private var lastNotice = 0

    suspend fun handle(ctx: Context, method: String, url: String, body: String): String {
        val path = url.substringBefore("?")
        val q = parseQuery(url.substringAfter("?", ""))
        return when {
            method == "POST" && path == "/api/cmd" -> {
                cmd(ctx, JSONObject(body.ifBlank { "{}" }))
                "{\"ok\":1}"
            }
            path == "/api/state" -> state(ctx).toString()
            path == "/api/lists" -> lists().toString()
            path == "/api/list" -> rows(items(q["n"] ?: "")).toString()
            path == "/api/home" -> home().toString()
            path == "/api/search" -> search(q["q"] ?: "").toString()
            path == "/api/find" -> find(q["q"] ?: "").toString()
            path == "/api/profile" -> profile().toString()
            path == "/api/settings" -> settings(ctx).toString()
            path == "/api/about" -> about(ctx).toString()
            path == "/api/eq" -> JSONObject().put("presets", JSONArray(Engine.eqNames)).toString()
            else -> "{}"
        }
    }

    private fun parseQuery(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val m = HashMap<String, String>()
        s.split("&").forEach {
            val k = it.substringBefore("=")
            val v = it.substringAfter("=", "")
            m[k] = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
        }
        return m
    }

    // ------------------------------------------------------------ durum
    private fun state(ctx: Context): JSONObject {
        val queue = Engine.queue.value
        val idx = Engine.index.value
        val song = queue.getOrNull(idx)
        val durMs = Engine.player.duration
        val dur = if (durMs != C.TIME_UNSET && durMs > 0) durMs / 1000.0 else (song?.durationSec ?: 0L).toDouble()
        val pos = Engine.player.currentPosition / 1000.0
        val lyr = Engine.lyrics.value
        val synced = lyr.synced
        val lines: List<String> = when {
            synced.isNotEmpty() -> synced.map { it.text }
            lyr.plain.isNotBlank() -> lyr.plain.lines().filter { it.isNotBlank() }
            lyr.status.isNotBlank() -> listOf(lyr.status)
            else -> emptyList()
        }.take(300)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVol

        val tn = Prefs.theme.value.coerceIn(0, Themes.accents.lastIndex)
        val argb = Themes.accents[tn].toArgb()
        val r = (argb shr 16) and 255
        val g = (argb shr 8) and 255
        val b = argb and 255
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val second = if (hsv[0] < 150f || hsv[0] > 290f) intArrayOf(77, 184, 255) else intArrayOf(255, 95, 162)
        val colors = JSONArray()
            .put(JSONArray().put(r).put(g).put(b))
            .put(JSONArray().put(second[0]).put(second[1]).put(second[2]))

        val cover = Engine.coverId.value
        val n = Engine.notice.value
        val toasts = JSONArray()
        if (n.id != lastNotice) {
            lastNotice = n.id
            if (n.text.isNotEmpty()) toasts.put(n.text)
        }

        val u = Updater.available.value
        val prog = Updater.progress.value
        val upd: Any = if (u == null) JSONObject.NULL else JSONObject()
            .put("v", u.tag.removePrefix("v"))
            .put("notes", JSONArray(u.notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(6)))
            .put("forced", false)
            .put("state", if (prog >= 0) "install" else "ask")
            .put("pct", maxOf(0, prog))
            .put("msg", if (prog >= 0) "İndiriliyor..." else "")

        val favs = Engine.favorites.value
        return JSONObject()
            .put("ok", 1)
            .put("plat", "android")
            .put("title", song?.title ?: "")
            .put("artist", song?.artist ?: "")
            .put("dur", dur)
            .put("pos", pos)
            .put("playing", Engine.playing.value)
            .put("loading", Engine.loading.value)
            .put("vol", vol)
            .put("key", song?.key ?: "")
            .put("idx", idx)
            .put("cid", if (cover != null) (cover.hashCode() and 0x7fffffff) % 1000000007 + 1 else 0)
            .put("curl", if (cover != null) "https://i.ytimg.com/vi/$cover/hqdefault.jpg" else "")
            .put("colors", colors)
            .put("lyrics", JSONArray(lines))
            .put("lsig", (lines.take(6).hashCode() and 0x7fffffff) % 1000000007 + lines.size)
            .put("fav", song != null && favs.any { it.key == song.key })
            .put("times", if (synced.isNotEmpty()) JSONArray(synced.take(300).map { it.sec }) else JSONObject.NULL)
            .put("off", 0)
            .put("user", "Gardaş")
            .put("ver", Updater.currentName(ctx))
            .put("anim", Themes.animNames[Prefs.anim.value.coerceIn(0, Themes.animNames.lastIndex)])
            .put("shuffle", Engine.shuffle.value)
            .put("repeat", Engine.repeatMode.value)
            .put("rate", Engine.speed.value.toDouble())
            .put("eq", Engine.eqNames[Engine.eqPreset.value.coerceIn(0, Engine.eqNames.lastIndex)])
            .put("sleep", Engine.sleepMin.value)
            .put("toasts", toasts)
            .put("found", JSONObject.NULL)
            .put("upd", upd)
            .put("login", JSONObject().put("need", false))
    }

    // ------------------------------------------------------------ listeler
    private fun items(n: String): List<Song> = when {
        n == FAV -> Engine.favorites.value
        n == RECENT -> Engine.recent.value
        n == QUEUE -> Engine.queue.value
        n == DOWNLOADS -> Downloads.items.value.map { it.song }
        n == RESULTS -> results
        n.startsWith(PL_PREFIX) -> Engine.playlists.value.firstOrNull { it.name == n.removePrefix(PL_PREFIX) }?.songs ?: emptyList()
        n.startsWith("tag:") -> Engine.songsWithTag(n.removePrefix("tag:"))
        else -> CATALOG[n] ?: emptyList()
    }

    private fun rows(list: List<Song>): JSONArray {
        val favKeys = Engine.favorites.value.map { it.key }.toHashSet()
        val a = JSONArray()
        list.take(400).forEach { s ->
            a.put(
                JSONObject()
                    .put("t", s.title)
                    .put("a", s.artist)
                    .put("d", s.durationSec)
                    .put("f", s.key in favKeys)
                    .put("g", JSONArray(Engine.tagsFor(s)))
            )
        }
        return a
    }

    private fun lists(): JSONArray {
        val a = JSONArray()
        fun add(n: String, label: String, c: Int, g: Int, r: Int = 0) {
            a.put(JSONObject().put("n", n).put("label", label).put("c", c).put("g", g).put("r", r))
        }
        add(FAV, FAV, Engine.favorites.value.size, 0, 1)
        add(RECENT, RECENT, Engine.recent.value.size, 0)
        add(QUEUE, QUEUE, Engine.queue.value.size, 0)
        add(DOWNLOADS, DOWNLOADS, Downloads.items.value.size, 0)
        Engine.playlists.value.forEach { add(PL_PREFIX + it.name, it.name, it.songs.size, 1, 1) }
        Engine.allTags().forEach { add("tag:" + it.first, "#" + it.first, it.second, 2) }
        CATALOG.forEach { add(it.key, it.key, it.value.size, 2) }
        return a
    }

    private fun home(): JSONArray {
        val a = JSONArray()
        a.put(JSONObject().put("id", "shuffle").put("t", "Karışık çal").put("s", "Tüm katalogdan rastgele"))
        a.put(JSONObject().put("id", "daily").put("t", "Bugünün karışığı").put("s", "Her gün yeni 30 şarkı"))
        Engine.moods.forEach { a.put(JSONObject().put("id", "mood:" + it[0]).put("t", it[0]).put("s", it[1])) }
        return a
    }

    // ------------------------------------------------------------ arama
    private suspend fun search(q0: String): JSONArray {
        val q = q0.trim()
        if (q.length < 2) return JSONArray()
        val ql = fold(q)
        val out = ArrayList<Song>()
        CATALOG.values.flatten().forEach {
            if (out.size < 30 && fold("${it.artist} ${it.title}").contains(ql) && out.none { o -> o.key == it.key }) out.add(it)
        }
        if (Engine.isOnline()) {
            try {
                Extractor.search(q).forEach { s -> if (out.none { it.key == s.key }) out.add(s) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // internet ya da YouTube sorunu: yerel sonuçlar yine de gösterilir
            }
        }
        results = out
        return rows(out)
    }

    private suspend fun find(q0: String): JSONArray {
        val q = q0.trim()
        if (q.length < 6 || !Engine.isOnline()) return JSONArray()
        val out = ArrayList<Song>()
        for (qq in listOf("$q şarkı sözleri", "\"$q\" lyrics")) {
            try {
                Extractor.search(qq).take(6).forEach { s -> if (out.none { it.key == s.key }) out.add(s) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
        results = out
        return rows(out)
    }

    // ------------------------------------------------------------ profil / ayarlar / hakkında
    private fun profile(): JSONObject {
        val w = Store.weeklyStats()
        val artists = JSONArray()
        w.second?.let { artists.put(JSONObject().put("a", it).put("s", 0)) }
        return JSONObject()
            .put("name", "Gardaş FM")
            .put("status", "Telefon sürümü")
            .put("id", "")
            .put("avatar", false)
            .put("total", "")
            .put("month", "Son 7 günde ${w.first} şarkı")
            .put("artists", artists)
            .put("songs", JSONArray())
            .put("founders", JSONArray())
    }

    private fun settings(ctx: Context): JSONObject {
        val v = JSONObject()
            .put("theme", Themes.names[Prefs.theme.value.coerceIn(0, Themes.names.lastIndex)])
            .put("anim", Themes.animNames[Prefs.anim.value.coerceIn(0, Themes.animNames.lastIndex)])
            .put("dim", Prefs.dim.value.toDouble())
            .put("blur", Prefs.blur.value.toDouble())
            .put("bg", Prefs.bgPath.value.isNotEmpty())
        return JSONObject()
            .put("plat", "android")
            .put("v", v)
            .put("o", JSONObject().put("themes", JSONArray(Themes.names)))
            .put("ver", Updater.currentName(ctx))
            .put("data", "")
            .put("audd", false)
    }

    private fun about(ctx: Context): JSONObject {
        val notes = JSONArray()
            .put(JSONArray().put(Updater.currentName(ctx)).put(JSONArray(listOf(
                "Yeni dikey arayüz: PC sürümüyle aynı görünüm",
                "Küçük oynatıcı, tam ekran oynatıcı, canlı sözler",
                "Ekolayzır, hız, uyku zamanlayıcı, etiketler, listeler",
            ))))
        return JSONObject().put("notes", notes).put("version", Updater.currentName(ctx))
    }

    // ------------------------------------------------------------ komutlar
    private fun song(c: JSONObject, nKey: String = "n", iKey: String = "i"): Song? =
        items(c.optString(nKey)).getOrNull(c.optInt(iKey, -1))

    private fun addTo(target: String, s: Song) {
        if (target == FAV) {
            if (Engine.favorites.value.none { it.key == s.key }) Engine.toggleFavorite(s)
        } else if (target.startsWith(PL_PREFIX)) {
            Engine.addToPlaylist(target.removePrefix(PL_PREFIX), s)
        }
    }

    private fun cmd(ctx: Context, c: JSONObject) {
        when (c.optString("cmd")) {
            "toggle" -> Engine.togglePlay()
            "next" -> Engine.next()
            "prev" -> Engine.prev()
            "seek" -> Engine.seekFraction(c.optDouble("f", 0.0).toFloat().coerceIn(0f, 1f))
            "vol" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (c.optInt("v", 70) * max / 100).coerceIn(0, max), 0)
            }
            "shuffle" -> Engine.toggleShuffle()
            "repeat" -> Engine.cycleRepeat()
            "sleep" -> Engine.setSleep(c.optInt("m", 0))
            "eq" -> {
                val i = Engine.eqNames.indexOf(c.optString("name"))
                if (i >= 0) Engine.setEq(i)
            }
            "rate" -> Engine.setSpeed(c.optDouble("v", 1.0).toFloat())
            "fav_now" -> Engine.queue.value.getOrNull(Engine.index.value)?.let { Engine.toggleFavorite(it) }
            "fav_row" -> song(c)?.let { Engine.toggleFavorite(it) }
            "enqueue" -> song(c)?.let { Engine.addToQueue(it) }
            "dl_row" -> song(c)?.let { Downloads.download(it) }
            "dl_list" -> items(c.optString("n")).forEach { Downloads.download(it) }
            "add_to_list" -> song(c)?.let { addTo(c.optString("t"), it) }
            "new_list" -> {
                val nm = c.optString("name").trim()
                if (Engine.createPlaylist(nm)) {
                    song(c, "add_n", "add_i")?.let { Engine.addToPlaylist(nm, it) }
                } else {
                    Engine.say("Bu isimde bir liste var ya da isim boş")
                }
            }
            "tag" -> song(c)?.let { s ->
                val tags = c.optString("tags").split(",").map { it.replace("#", "").trim().lowercase() }
                    .filter { it.isNotEmpty() }.distinct().take(8)
                Engine.setTags(s, tags)
            }
            "move" -> {
                val n = c.optString("n")
                val i = c.optInt("i")
                val j = c.optInt("j")
                if (n == FAV) Engine.moveFavorite(i, j)
                else if (n.startsWith(PL_PREFIX)) Engine.movePlaylistSong(n.removePrefix(PL_PREFIX), i, j)
            }
            "play_list" -> {
                val l = items(c.optString("n"))
                val i = c.optInt("i", 0)
                if (i in l.indices) Engine.playList(l, i)
            }
            "home" -> {
                val id = c.optString("id")
                when {
                    id == "shuffle" -> Engine.playAllShuffled()
                    id == "daily" -> Engine.playList(Engine.todaysMix(), 0)
                    id.startsWith("mood:") -> Engine.playMood(id.removePrefix("mood:"))
                }
            }
            "set" -> set(c.optString("k"), c.opt("v"))
            "upd_check" -> Updater.check(ctx, true)
            "upd_install" -> Updater.available.value?.let { Updater.downloadAndInstall(ctx, it) }
            "upd_later" -> Updater.available.value = null
            "import_yt" -> Engine.importYoutubePlaylist(c.optString("url"))
            "pick_bg" -> hooks?.pickBackground()
            "pick_folder" -> hooks?.pickFolder()
            "clear_bg" -> Prefs.clearBackground()
            "quit" -> hooks?.quit()
        }
    }

    private fun set(k: String, v: Any?) {
        when (k) {
            "theme" -> {
                val i = Themes.names.indexOf(v as? String ?: "")
                if (i >= 0) Prefs.setTheme(i)
            }
            "anim" -> {
                val i = Themes.animNames.indexOf(v as? String ?: "")
                if (i >= 0) Prefs.setAnim(i)
            }
            "dim" -> (v as? Number)?.let { Prefs.setDim(it.toFloat().coerceIn(0f, 0.9f)) }
            "blur" -> (v as? Number)?.let { Prefs.setBlur(it.toFloat().coerceIn(0f, 20f)) }
        }
    }
}
