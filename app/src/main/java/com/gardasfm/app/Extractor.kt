package com.gardasfm.app

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

/** NewPipeExtractor'un ihtiyac duydugu HTTP istemcisi (OkHttp). */
class OkDownloader : Downloader() {
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
        request.headers().forEach { (k, vs) -> b.removeHeader(k); vs.forEach { v -> b.addHeader(k, v) } }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("429", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

object Extractor {
    private val http = OkHttpClient()

    fun init() = NewPipe.init(OkDownloader(), Localization("tr", "TR"))

    /** YouTube Music "sarkilar" filtresiyle ara; bos donerse duz arama. */
    fun search(q: String): List<Item> {
        val yt = ServiceList.YouTube
        fun run(filters: List<String>): List<Item> {
            val h = yt.searchQHFactory.fromQuery(q, filters, "")
            return SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<StreamInfoItem>().map {
                Item((it.uploaderName ?: "").removeSuffix(" - Topic"), it.name ?: "?", it.url,
                    it.duration.toInt().coerceAtLeast(0), it.thumbnails.lastOrNull()?.url ?: "")
            }.filter { it.url.isNotEmpty() }
        }
        val songs = try { run(listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)) } catch (e: Exception) { emptyList() }
        return songs.ifEmpty { run(emptyList()) }.distinctBy { it.url }
    }

    /** Dogrudan calinabilir ses akisi adresi (en yuksek bitrate, once duz HTTP). */
    fun streamUrl(videoUrl: String): String {
        val info = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
        val all = info.audioStreams
        val a = all.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.maxByOrNull { it.averageBitrate }
            ?: all.maxByOrNull { it.averageBitrate } ?: error("ses akisi yok")
        return a.content
    }

    fun cover(item: Item): ByteArray? = try {
        val id = Regex("[?&]v=([\\w-]{11})").find(item.url)?.groupValues?.get(1)
        val url = item.thumb.ifEmpty { if (id != null) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else "" }
        if (url.isEmpty()) null else http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
    } catch (e: Exception) { null }
}
