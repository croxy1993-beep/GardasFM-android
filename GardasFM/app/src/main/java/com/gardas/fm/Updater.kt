package com.gardas.fm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(val code: Int, val tag: String, val notes: String, val url: String)

/**
 * Otomatik güncelleme: GitHub Releases'teki en son APK'yı bulur, indirir, kurulum ekranını açar.
 * Sürüm numarası = GitHub derleme sırası (workflow bunu hem tag'e hem versionCode'a yazar).
 */
object Updater {
    /** Güncellemelerin yayınlandığı GitHub deposu (PUBLIC olmalı). */
    const val REPO = "croxy1993-beep/GardasFM2"

    val available = MutableStateFlow<UpdateInfo?>(null)
    val progress = MutableStateFlow(-1)          // -1: indirme yok, 0..100: iniyor
    val checking = MutableStateFlow(false)
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    fun currentCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** manual=false: açılışta sessizce (en fazla 6 saatte bir). manual=true: Görünüm'deki düğmeyle. */
    fun check(ctx: Context, manual: Boolean) {
        if (checking.value) return
        val last = Store.getStr("upd_last", "0").toLongOrNull() ?: 0L
        if (!manual && System.currentTimeMillis() - last < 6L * 3600 * 1000) return
        checking.value = true
        Engine.scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "GardasFM")
                    .build()
                val body = client.newCall(req).execute().use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body?.string() ?: ""
                }
                val o = JSONObject(body)
                val tag = o.optString("tag_name", "")
                val code = Regex("\\d+").findAll(tag).lastOrNull()?.value?.toIntOrNull() ?: 0
                var url = ""
                val assets = o.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Store.putStr("upd_last", System.currentTimeMillis().toString())
                    if (url.isNotEmpty() && code > currentCode(ctx)) {
                        available.value = UpdateInfo(code, tag, o.optString("body", ""), url)
                    } else if (manual) {
                        Engine.say("Gardaş FM güncel (sürüm ${currentName(ctx)})")
                    }
                }
            } catch (e: Exception) {
                if (manual) withContext(Dispatchers.Main) { Engine.say("Güncelleme kontrol edilemedi") }
            } finally {
                checking.value = false
            }
        }
    }

    fun downloadAndInstall(ctx: Context, info: UpdateInfo) {
        if (progress.value >= 0) return
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "GardasFM.apk")
        if (out.exists() && out.length() > 0 && Store.getInt("apk_ready_code", 0) == info.code) {
            install(ctx, out)       // daha önce indirilmiş (izin verip geri dönünce tekrar indirme)
            return
        }
        progress.value = 0
        Engine.scope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(info.url).header("User-Agent", "GardasFM").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("boş yanıt")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                                done += n
                                if (total > 0) progress.value = (done * 100 / total).toInt()
                            }
                        }
                    }
                }
                Store.putInt("apk_ready_code", info.code)
                withContext(Dispatchers.Main) {
                    progress.value = -1
                    install(ctx, out)
                }
            } catch (e: Exception) {
                out.delete()
                progress.value = -1
                withContext(Dispatchers.Main) { Engine.say("Güncelleme indirilemedi: ${e.message?.take(50) ?: "hata"}") }
            }
        }
    }

    fun install(ctx: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Engine.say("Açılan ekranda Gardaş FM için 'yüklemeye izin ver'i aç, geri dönüp tekrar Güncelle'ye bas")
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
