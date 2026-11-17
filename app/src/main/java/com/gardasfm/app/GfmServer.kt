package com.gardasfm.app

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

/** PC 9.0 arayuzunun bekledigi API'nin telefon surumu. Sadece 127.0.0.1'e baglanir, gizli anahtar ister. */
class GfmServer : NanoHTTPD("127.0.0.1", Engine.PORT) {

    private fun json(body: Any, code: Response.IStatus = Response.Status.OK): Response =
        newFixedLengthResponse(code, "application/json; charset=utf-8", body.toString()).also { it.addHeader("Cache-Control", "no-store") }

    private fun asset(name: String) = Engine.app.assets.open(name).use { it.readBytes() }

    private fun rows(l: List<Item>) = JSONArray().also { a ->
        l.take(400).forEach { s ->
            a.put(JSONObject().put("t", s.title).put("a", s.artist).put("d", s.dur).put("f", Engine.isFav(s)).put("g", JSONArray()))
        }
    }

    override fun serve(s: IHTTPSession): Response {
        val host = (s.headers["host"] ?: "").substringBefore(":")
        if ((host != "127.0.0.1" && host != "localhost") || s.parms["t"] != Engine.token) return json("{}", Response.Status.FORBIDDEN)
        return try {
            if (s.method == Method.POST) {
                val files = HashMap<String, String>(); s.parseBody(files)
                Engine.cmd(JSONObject(files["postData"] ?: "{}")); return json("{\"ok\":1}")
            }
            when (s.uri) {
                "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", String(asset("index.html")))
                "/api/logo" -> newFixedLengthResponse(Response.Status.OK, "image/png", asset("logo.png").inputStream(), asset("logo.png").size.toLong())
                "/api/cover" -> Engine.cover?.let { newFixedLengthResponse(Response.Status.OK, "image/jpeg", it.inputStream(), it.size.toLong()) }
                    ?: newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "")
                "/api/avatar" -> newFixedLengthResponse(Response.Status.NO_CONTENT, "image/png", "")
                "/api/state" -> json(state())
                "/api/home" -> json(home())
                "/api/lists" -> json(lists())
                "/api/list" -> json(rows(Engine.itemsOf(s.parms["n"] ?: "")))
                "/api/search" -> json(rows(Engine.search(s.parms["q"] ?: "")))
                "/api/settings" -> json(settings())
                "/api/profile" -> json(JSONObject("""{"name":"Gardaş","status":"","id":"","avatar":false,"total":"0 dk","month":"0 dk","artists":[],"songs":[],"founders":[]}"""))
                "/api/about" -> json(JSONObject("""{"notes":[["Android 1.0",["İlk Android sürümü: PC 9.0 arayüzü, arama, çalma, favoriler"]]],"version":"Android 1.0"}"""))
                "/api/eq" -> json(JSONObject("""{"presets":["Düz"]}"""))
                "/api/find" -> json(JSONObject())
                else -> json("{}", Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) { json(JSONObject().put("err", "${e.javaClass.simpleName}: ${e.message}".take(200)), Response.Status.INTERNAL_ERROR) }
    }

    private fun state(): JSONObject {
        val c = Engine.cur(); val sn = Engine.snap
        val toasts = JSONArray(); while (true) { toasts.put(Engine.toasts.poll() ?: break) }
        val q = JSONArray().also { a -> Engine.queue.take(300).forEach { a.put(JSONObject().put("t", it.title).put("a", it.artist).put("d", it.dur)) } }
        return JSONObject().put("ok", 1).put("title", c?.title ?: "").put("artist", c?.artist ?: "")
            .put("dur", if (sn.dur > 0) sn.dur else (c?.dur ?: 0).toDouble()).put("pos", sn.pos).put("playing", sn.playing)
            .put("vol", Engine.vol).put("key", c?.url ?: "").put("idx", Engine.idx).put("cid", Engine.cid)
            .put("colors", JSONArray("[[255,79,139],[140,80,255]]")).put("lyrics", JSONArray()).put("lsig", 0)
            .put("fav", c != null && Engine.isFav(c)).put("times", JSONObject.NULL).put("off", 0).put("queue", q)
            .put("user", "Gardaş").put("ver", "Android 1.0").put("anim", "Tam").put("shuffle", Engine.shuffle)
            .put("repeat", Engine.repeat).put("rate", 1.0).put("eq", "Düz").put("sleep", 0).put("toasts", toasts)
            .put("found", JSONObject.NULL).put("upd", JSONObject.NULL)
            .put("login", JSONObject("""{"need":false,"denied":false,"busy":false,"msg":"","invite":"","tiktok":"","channel":""}"""))
    }

    private fun home(): JSONArray {
        val a = JSONArray()
        fun card(id: String, t: String, s: String) = a.put(JSONObject().put("id", id).put("t", t).put("s", s))
        card("shuffle", "Karışık çal", "Favorilerinden ve son çalınanlardan"); card("daily", "Bugünün karışığı", "Yeni çıkan şarkılar")
        card("radio", "Radyo", "Çalan şarkıdan sonsuz yayın")
        card("mood:Sakin", "Sakin", "Yavaş, duygusal, çay yanında"); card("mood:Hareketli", "Hareketli", "Tempo yüksek, enerji tam")
        card("mood:Gece sürüşü", "Gece sürüşü", "Cam açık, yol boş"); card("mood:Çalışma", "Çalışma", "Dikkat dağıtmayan akış")
        return a
    }

    private fun lists(): JSONArray {
        val a = JSONArray()
        fun l(n: String, c: Int, r: Int) = a.put(JSONObject().put("n", n).put("label", n).put("c", c).put("g", 0).put("r", r))
        l(Engine.MIX, Engine.favs.size, 1); l(Engine.RECENT, Engine.recent.size, 0); l(Engine.QUEUE, Engine.queue.size, 1)
        return a
    }

    private fun settings(): JSONObject {
        val d = mapOf("theme" to "Gece İstanbul", "anim" to "Tam", "cover_color" to true, "notify" to true, "fade" to 0,
            "song_vol_on" to false, "auto_update" to true, "offline" to false, "tray" to false, "discord" to false)
        val v = JSONObject(); d.forEach { (k, def) -> v.put(k, if (Engine.settings.has(k)) Engine.settings.get(k) else def) }
        val themes = JSONArray(listOf("Gece İstanbul", "Okyanus", "Orman", "Gün batımı", "Mor sis", "Neon Siber", "Kırmızı Kadife", "Gece Mavisi", "Altın Saat"))
        return JSONObject().put("v", v).put("o", JSONObject().put("themes", themes)).put("audd", false).put("ver", "Android 1.0").put("data", "")
    }
}
