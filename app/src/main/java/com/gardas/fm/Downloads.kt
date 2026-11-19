package com.gardas.fm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

const val DOWNLOADS = "İndirilenler"

/** İndirilmiş bir şarkı: şarkı bilgisi + dosya adı + (varsa) YouTube video kimliği. */
data class DlItem(val song: Song, val file: String, val videoId: String?)

/** Çevrimdışı dinleme: sesi uygulamanın kendi klasörüne indirir, internet yokken oradan çalar. */
object Downloads {
    val items = MutableStateFlow<List<DlItem>>(emptyList())
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())   // şarkı anahtarı -> yüzde (-1: hata)

    private lateinit var dir: File
    private val client = OkHttpClient.Builder().readTimeout(40, TimeUnit.SECONDS).build()
    private val gate = Semaphore(2)        // aynı anda en fazla 2 indirme (toplu indirmede telefonu yormasın)

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "muzik").apply { mkdirs() }
        items.value = load()
    }

    private fun load(): List<DlItem> = try {
        val a = JSONArray(Store.getStr("downloads", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            DlItem(
                Song(
                    o.optString("artist", ""),
                    o.getString("title"),
                    if (o.has("url") && !o.isNull("url")) o.getString("url") else null,
                    o.optLong("dur", 0),
                ),
                o.getString("file"),
                if (o.has("vid") && !o.isNull("vid")) o.getString("vid") else null,
            )
        }.filter { File(dir, it.file).exists() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save() {
        val a = JSONArray()
        items.value.forEach {
            a.put(
                JSONObject()
                    .put("artist", it.song.artist).put("title", it.song.title)
                    .put("url", it.song.url ?: JSONObject.NULL).put("dur", it.song.durationSec)
                    .put("file", it.file).put("vid", it.videoId ?: JSONObject.NULL)
            )
        }
        Store.putStr("downloads", a.toString())
    }

    fun has(song: Song): Boolean = fileFor(song) != null

    fun fileFor(song: Song): File? =
        items.value.firstOrNull { it.song.key == song.key }?.let { File(dir, it.file) }?.takeIf { it.exists() }

    fun videoIdFor(song: Song): String? = items.value.firstOrNull { it.song.key == song.key }?.videoId

    private fun setProgress(key: String, p: Int?) {
        val m = progress.value.toMutableMap()
        if (p == null) m.remove(key) else m[key] = p
        progress.value = m
    }

    fun download(song: Song) {
        if (song.isLocal || has(song) || progress.value.containsKey(song.key)) return
        val key = song.key
        setProgress(key, 0)
        Engine.scope.launch(Dispatchers.IO) {
          gate.withPermit {
            var out: File? = null
            try {
                val r = Extractor.resolve(song)
                val name = "${Math.abs(key.hashCode())}_${System.currentTimeMillis()}.${r.ext}"
                out = File(dir, name)
                var start = 0L
                var total = -1L
                val chunk = 1_048_576L        // 1 MB'lık parçalarla indir (YouTube tek parça indirmeyi yavaşlatabiliyor)
                out.outputStream().use { os ->
                    while (total < 0 || start < total) {
                        val req = Request.Builder().url(r.streamUrl)
                            .header("User-Agent", DownloaderImpl.USER_AGENT)
                            .header("Range", "bytes=$start-${start + chunk - 1}")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            if (total < 0) {
                                total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                    ?: resp.body?.contentLength() ?: -1L
                            }
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            if (bytes.isEmpty()) throw IOException("boş yanıt")
                            os.write(bytes)
                            start += bytes.size
                        }
                        if (total > 0) setProgress(key, (start * 100 / total).toInt().coerceIn(0, 99))
                        if (total < 0) break      // uzunluk bilinmiyorsa tek parça yeterli
                    }
                }
                val item = DlItem(song, name, r.videoId)
                items.value = items.value.filter { it.song.key != key } + item
                save()
                setProgress(key, null)
                Engine.say("İndirildi: ${song.title}")
            } catch (e: Exception) {
                out?.delete()
                setProgress(key, null)
                Engine.say("İndirilemedi: ${e.message?.take(50) ?: "hata"}")
            }
          }
        }
    }

    fun delete(song: Song) {
        items.value.firstOrNull { it.song.key == song.key }?.let { File(dir, it.file).delete() }
        items.value = items.value.filter { it.song.key != song.key }
        save()
    }
}
