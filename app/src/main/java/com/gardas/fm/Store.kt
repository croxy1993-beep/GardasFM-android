package com.gardas.fm

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bir şarkı. url boşsa katalog şarkısıdır (çalarken YouTube'da aranır). content:// ya da file:// ise yerel dosyadır. */
data class Song(
    val artist: String,
    val title: String,
    val url: String? = null,
    val durationSec: Long = 0,
) {
    val key: String get() = url ?: "$artist - $title"
    val label: String get() = if (artist.isNotBlank()) "$artist - $title" else title
    val isLocal: Boolean get() = url?.startsWith("content:") == true || url?.startsWith("file:") == true
}

/** İsimli çalma listesi. */
data class Playlist(val name: String, val songs: List<Song>)

fun songsToJson(list: List<Song>): JSONArray {
    val a = JSONArray()
    list.forEach {
        a.put(
            JSONObject()
                .put("artist", it.artist)
                .put("title", it.title)
                .put("url", it.url ?: JSONObject.NULL)
                .put("dur", it.durationSec)
        )
    }
    return a
}

fun songsFromJson(a: JSONArray): List<Song> = (0 until a.length()).map {
    val o = a.getJSONObject(it)
    Song(
        o.optString("artist", ""),
        o.getString("title"),
        if (o.has("url") && !o.isNull("url")) o.getString("url") else null,
        o.optLong("dur", 0),
    )
}

/** Basit kalıcı depo (SharedPreferences + JSON). */
object Store {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("gardas", Context.MODE_PRIVATE)
    }

    fun getStr(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun getFloat(k: String, d: Float): Float = sp.getFloat(k, d)
    fun putFloat(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    fun getInt(k: String, d: Int): Int = sp.getInt(k, d)
    fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    fun getBool(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    fun getSongs(k: String): List<Song> = try {
        songsFromJson(JSONArray(getStr(k, "[]")))
    } catch (e: Exception) {
        emptyList()
    }

    fun putSongs(k: String, list: List<Song>) = putStr(k, songsToJson(list).toString())

    fun getList(k: String): List<String> = try {
        val a = JSONArray(getStr(k, "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun putList(k: String, list: List<String>) {
        val a = JSONArray()
        list.forEach { a.put(it) }
        putStr(k, a.toString())
    }

    // ---------------------------------------------------------------- çalma listeleri
    fun getPlaylists(): List<Playlist> = try {
        val a = JSONArray(getStr("playlists", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Playlist(o.getString("name"), songsFromJson(o.getJSONArray("songs")))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun putPlaylists(list: List<Playlist>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("songs", songsToJson(it.songs))) }
        putStr("playlists", a.toString())
    }

    // ---------------------------------------------------------------- dinleme kaydı (haftalık özet için)
    fun addPlay(song: Song) {
        try {
            val a = JSONArray(getStr("playlog", "[]"))
            a.put(JSONObject().put("t", System.currentTimeMillis()).put("a", song.artist).put("ti", song.title))
            val start = maxOf(0, a.length() - 600)
            val out = JSONArray()
            for (i in start until a.length()) out.put(a.get(i))
            putStr("playlog", out.toString())
        } catch (e: Exception) {
            // kayıt tutulamazsa önemli değil
        }
    }

    /** Son 7 günde (dinlenen şarkı sayısı, en çok dinlenen sanatçı). */
    fun weeklyStats(): Pair<Int, String?> = try {
        val a = JSONArray(getStr("playlog", "[]"))
        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val counts = HashMap<String, Int>()
        var n = 0
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getLong("t") >= since) {
                n++
                val art = o.optString("a", "")
                if (art.isNotBlank()) counts[art] = (counts[art] ?: 0) + 1
            }
        }
        n to counts.maxByOrNull { it.value }?.key
    } catch (e: Exception) {
        0 to null
    }
}

/** Görünüm ayarları: arka plan fotoğrafı, karartma, bulanıklık, renk teması. */
object Prefs {
    val bgPath = MutableStateFlow(Store.getStr("bg"))
    val dim = MutableStateFlow(Store.getFloat("dim", 0.45f))
    val blur = MutableStateFlow(Store.getFloat("blur", 6f))
    val theme = MutableStateFlow(Store.getInt("theme", 0))
    val anim = MutableStateFlow(Store.getInt("anim", 0))      // 0 Tam, 1 Hafif, 2 Kapalı

    fun setAnim(i: Int) {
        anim.value = i
        Store.putInt("anim", i)
    }

    fun setTheme(i: Int) {
        theme.value = i
        Store.putInt("theme", i)
    }

    fun setDim(v: Float) {
        dim.value = v
        Store.putFloat("dim", v)
    }

    fun setBlur(v: Float) {
        blur.value = v
        Store.putFloat("blur", v)
    }

    /** Seçilen resmi uygulamanın kendi klasörüne kopyalar (orijinal silinse bile bozulmaz). */
    fun setBackground(ctx: Context, uri: Uri) {
        Engine.scope.launch(Dispatchers.IO) {
            try {
                val dest = File(ctx.filesDir, "bg_${System.currentTimeMillis()}.img")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                if (decodeScaled(dest.absolutePath) == null) {
                    dest.delete()
                    return@launch
                }
                clearFile()
                Store.putStr("bg", dest.absolutePath)
                bgPath.value = dest.absolutePath
            } catch (e: Exception) {
                // resim açılamadıysa eski arka plan kalır
            }
        }
    }

    fun clearBackground() {
        clearFile()
        Store.putStr("bg", "")
        bgPath.value = ""
    }

    private fun clearFile() {
        val old = bgPath.value
        if (old.isNotEmpty()) File(old).delete()
    }
}

/** Büyük fotoğrafları ekrana yetecek boyuta küçülterek açar (EXIF dönmesini de uygular). */
fun decodeScaled(path: String, maxDim: Int = 1280): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            var s = 1
            while (big / s > maxDim) s *= 2
            decoder.setTargetSampleSize(s)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / s > maxDim) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }
} catch (e: Exception) {
    null
}
