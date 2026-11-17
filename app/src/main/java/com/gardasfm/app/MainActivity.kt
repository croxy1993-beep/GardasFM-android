package com.gardasfm.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0a0720")
        window.navigationBarColor = Color.parseColor("#0a0720")
        Engine.start(applicationContext)                       // oynatici + yerel sunucu
        startService(Intent(this, PlaybackService::class.java)) // bildirim ve arka plan
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0a0720"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
        }
        setContentView(web)
        web.loadUrl("http://127.0.0.1:${Engine.PORT}/?t=${Engine.token}")   // uygulamanin icindeki arayuz, internet sitesi degil
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { moveTaskToBack(true) }     // geri tusu muzigi kapatmasin
}
