package com.gardas.fm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** NewPipeExtractor'ın internete çıkması için gereken HTTP katmanı (OkHttp). */
class DownloaderImpl : Downloader() {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    override fun execute(request: Request): Response {
        val dataToSend = request.dataToSend()
        val body: RequestBody? = dataToSend?.toRequestBody(null, 0, dataToSend.size)
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)
        for ((name, values) in request.headers()) {
            if (values.size > 1) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            } else if (values.size == 1) {
                builder.header(name, values[0])
            }
        }
        val response = client.newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }
        val text = response.body?.string()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            text,
            response.request.url.toString(),
        )
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}

data class Resolved(val streamUrl: String, val videoId: String?, val durationSec: Long, val ext: String = "m4a")

private val MARKS = Regex("\\p{Mn}+")
private val SPACES = Regex("\\s+")

/** Arama için normalleştirme: büyük/küçük harf, Türkçe harfler (İ ı ş ğ ü ö ç) ve aksanlar yok sayılır. */
fun fold(s: String?): String {
    val t = (s ?: "")
        .replace('İ', 'i').replace('I', 'i').replace('ı', 'i')
        .replace('Ş', 's').replace('ş', 's').replace('Ğ', 'g').replace('ğ', 'g')
        .replace('Ü', 'u').replace('ü', 'u').replace('Ö', 'o').replace('ö', 'o')
        .replace('Ç', 'c').replace('ç', 'c')
    val n = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFKD).replace(MARKS, "")
    return n.replace(SPACES, " ").trim()
}

private val BAD_WORDS = listOf(
    "cover", "live", "canli", "remix", "karaoke", "instrumental", "8d", "slowed", "reverb",
    "sped up", "nightcore", "concert", "konser", "mashup", "reaction", "tutorial", "lesson",
)
private val BAD_REGEX = BAD_WORDS.map { Regex("\\b" + Regex.escape(it) + "\\b") }
private val WORD = Regex("\\w+")

/**
 * YouTube sonuçları arasından doğru kaydı seçer (PC sürümündeki pick_best ile aynı mantık):
 * cover/canlı/remix/karaoke ve yanlış süreyi eler, "Sanatçı - Topic" ve resmî kayıtları öne alır.
 */
fun pickBest(entries: List<Song>, artist: String, title: String): Song? {
    val wantT = fold(title)
    val wantA = fold(artist)
    val words = WORD.findAll(wantT).map { it.value }.filter { it.length > 2 }.toList()
    var best: Song? = null
    var bestScore = Int.MIN_VALUE
    entries.forEachIndexed { i, e ->
        val et = fold(e.title)
        val ch = fold(e.artist)
        var s = -2 * i
        for (re in BAD_REGEX) {
            if (re.containsMatchIn(et) && !re.containsMatchIn(wantT)) s -= 60
        }
        val d = e.durationSec
        if (d > 0) {
            if (d < 60 || d > 720) s -= 80 else if (d in 90..480) s += 10
        }
        if (wantA.isNotEmpty() && (et.contains(wantA) || ch.contains(wantA))) s += 30
        if (ch.endsWith("- topic")) s += 25
        if (et.contains("official") || et.contains("resmi") || et.contains("audio") || ch.contains("vevo")) s += 10
        s += 8 * words.count { et.contains(it) }
        if (s > bestScore) {
            best = e
            bestScore = s
        }
    }
    return best
}

object Extractor {
    private var ready = false
    private val idRegex = Regex("""(?:v=|youtu\.be/|/shorts/)([\w-]{11})""")

    @Synchronized
    private fun ensureInit() {
        if (!ready) {
            NewPipe.init(DownloaderImpl(), Localization("tr", "TR"), ContentCountry("TR"))
            ready = true
        }
    }

    fun videoId(url: String?): String? = url?.let { idRegex.find(it)?.groupValues?.get(1) }

    /** YouTube'da video arar. */
    suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        ensureInit()
        val service = ServiceList.YouTube
        val handler = service.searchQHFactory.fromQuery(query, listOf("videos"), "")
        val info = SearchInfo.getInfo(service, handler)
        info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .take(25)
            .map { Song(it.uploaderName ?: "", it.name ?: "?", it.url, it.duration) }
    }

    /** Şarkının çalınabilir ses linkini bulur (katalog şarkısıysa önce YouTube'da arar ve doğru kaydı seçer). */
    suspend fun resolve(song: Song): Resolved = withContext(Dispatchers.IO) {
        ensureInit()
        val url = song.url
            ?: pickBest(search("${song.artist} ${song.title}").take(12), song.artist, song.title)?.url
            ?: throw IllegalStateException("YouTube'da bulunamadı")
        val info = StreamInfo.getInfo(url)
        val audio = info.audioStreams.filter {
            !it.content.isNullOrBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val best = audio.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: audio.maxByOrNull { it.averageBitrate }
            ?: throw IllegalStateException("Çalınabilir ses akışı yok")
        Resolved(best.content, videoId(url), info.duration, best.format?.suffix ?: "m4a")
    }

    /** YouTube çalma listesini (ilk ~400 şarkı) içe aktarır: (liste adı, şarkılar). */
    suspend fun playlist(url: String): Pair<String, List<Song>> = withContext(Dispatchers.IO) {
        ensureInit()
        val info = PlaylistInfo.getInfo(url)
        val out = ArrayList<Song>()
        fun add(items: List<StreamInfoItem>) {
            items.forEach { out.add(Song(it.uploaderName ?: "", it.name ?: "?", it.url, it.duration)) }
        }
        add(info.relatedItems)
        var page = info.nextPage
        var guard = 0
        while (page != null && guard < 6 && out.size < 400) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
            add(more.items)
            page = more.nextPage
            guard++
        }
        (info.name ?: "YouTube listesi") to out
    }
}

/** Zamanlı şarkı sözü satırı. */
data class LrcLine(val sec: Double, val text: String)

data class Lyrics(
    val status: String = "",
    val plain: String = "",
    val synced: List<LrcLine> = emptyList(),
)

/** lrclib.net'ten (ücretsiz) senkronlu şarkı sözü alır; bulunan sözler internetsiz de açılsın diye diske saklanır. */
object LyricsApi {
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
    private val noise = Regex("""\(.*?\)|\[.*?]|official.*|video.*|lyrics.*|sözleri.*|audio.*""", RegexOption.IGNORE_CASE)

    fun parseLrc(text: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (line in text.lines()) {
            val stamps = stamp.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val lyric = stamp.replace(line, "").trim()
            for (m in stamps) {
                out.add(LrcLine(m.groupValues[1].toInt() * 60 + m.groupValues[2].toDouble(), lyric))
            }
        }
        return out.sortedBy { it.sec }
    }

    private fun JSONObject.str(k: String): String = if (has(k) && !isNull(k)) getString(k) else ""

    private fun cacheFile(song: Song): File {
        val dir = File(Engine.app.filesDir, "sozler").apply { mkdirs() }
        return File(dir, "${Math.abs(song.key.hashCode())}.json")
    }

    private fun readCache(song: Song): Lyrics? = try {
        val f = cacheFile(song)
        if (!f.exists()) {
            null
        } else {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("synced")
            val lines = (0 until arr.length()).map {
                val p = arr.getJSONArray(it)
                LrcLine(p.getDouble(0), p.getString(1))
            }
            Lyrics(plain = o.optString("plain", ""), synced = lines)
        }
    } catch (e: Exception) {
        null
    }

    private fun writeCache(song: Song, l: Lyrics) {
        try {
            val arr = JSONArray()
            l.synced.forEach { arr.put(JSONArray().put(it.sec).put(it.text)) }
            cacheFile(song).writeText(JSONObject().put("plain", l.plain).put("synced", arr).toString())
        } catch (e: Exception) {
            // önbellek yazılamazsa sorun değil
        }
    }

    suspend fun fetch(song: Song): Lyrics = withContext(Dispatchers.IO) {
        readCache(song)?.let { return@withContext it }
        try {
            val clean = song.title.replace(noise, "").trim()
            val q = if (song.url != null && !song.isLocal) clean else "${song.artist} $clean".trim()
            val req = okhttp3.Request.Builder()
                .url("https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8"))
                .header("User-Agent", "GardasFM/2.0")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() }
                ?: return@withContext Lyrics(status = "Bu şarkının sözü bulunamadı.")
            val arr = JSONArray(body)
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }
            val synced = items.firstOrNull { it.str("syncedLyrics").isNotBlank() }
            if (synced != null) {
                val lines = parseLrc(synced.str("syncedLyrics"))
                val plain = synced.str("plainLyrics").ifBlank { lines.joinToString("\n") { it.text } }
                val l = Lyrics(plain = plain, synced = lines)
                writeCache(song, l)
                return@withContext l
            }
            val plain = items.firstOrNull { it.str("plainLyrics").isNotBlank() }
            if (plain != null) {
                val l = Lyrics(plain = plain.str("plainLyrics"))
                writeCache(song, l)
                l
            } else {
                Lyrics(status = "Bu şarkının sözü bulunamadı.")
            }
        } catch (e: Exception) {
            Lyrics(status = "Sözler alınamadı.")
        }
    }
}
