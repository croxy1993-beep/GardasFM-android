package com.gardas.fm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Telefon arayüzü: PC'deki yeni arayüzün dikey (telefon) sürümü bir WebView içinde çalışır.
 * Müzik, indirme, listeler ve bildirim Engine'den gelir; WebApi iki tarafı konuşturur.
 */
class WebActivity : ComponentActivity() {
    private lateinit var web: WebView

    private val bgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Prefs.setBackground(this, uri)
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) Engine.importFolder(this, uri)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        web = WebView(this)
        web.setBackgroundColor(0xFF0A0720.toInt())
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                    }
                    return true
                }
                return false
            }
        }
        // Durum çubuğu, alt gezinme çubuğu ve klavye kadar boşluk bırak
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/ui/index.html")

        WebApi.hooks = object : WebApi.Hooks {
            override fun pickBackground() { bgPicker.launch("image/*") }
            override fun pickFolder() { folderPicker.launch(null) }
            override fun quit() { moveTaskToBack(true) }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Updater.check(this, false)       // açılışta sessizce yeni sürüm var mı bak

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__back&&window.__back())||false") { r ->
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })
    }

    override fun onDestroy() {
        WebApi.hooks = null
        web.removeJavascriptInterface("Android")
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun request(id: Int, method: String, url: String, body: String) {
            Engine.scope.launch {
                val out = try {
                    WebApi.handle(this@WebActivity, method, url, body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    JSONObject().put("err", "${e.javaClass.simpleName}: ${e.message}").toString()
                }
                web.evaluateJavascript("window.__res($id,${JSONObject.quote(out)})", null)
            }
        }
    }
}
