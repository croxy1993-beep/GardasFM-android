package com.gardas.fm

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Arka planda çalma + bildirimdeki kontroller (Media3 bildirimi otomatik üretir). */
class PlayerService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, Engine.sessionPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Uygulama son uygulamalardan kapatılınca müzik çalmıyorsa servisi de kapat
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()      // oynatıcıyı değil, sadece oturumu kapatır (oynatıcı Engine'in)
        session = null
        super.onDestroy()
    }
}
