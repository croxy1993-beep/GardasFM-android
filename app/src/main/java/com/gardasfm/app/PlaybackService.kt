package com.gardasfm.app

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Ekran kapaliyken de calmayi ve bildirimdeki kontrolleri saglar. */
class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (Engine.started) Engine.session else null
}
