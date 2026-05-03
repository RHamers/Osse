package com.rhamers.osse.player

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rhamers.osse.model.TrackId

class MultiTrackService : MediaSessionService() {

    private val tracks = mutableMapOf<TrackId, TrackPlayer>()
    private var mediaSession: MediaSession? = null

    inner class LocalBinder : Binder() {
        fun getTrack(id: TrackId): TrackPlayer? = tracks[id]
        fun getAllTracks(): Map<TrackId, TrackPlayer> = tracks.toMap()
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        TrackId.entries.forEach { id ->
            tracks[id] = TrackPlayer(this, id)
        }
        // MediaSession points to the podcast player — routes system notifications
        // and headphone buttons to the primary track. Music track runs headless.
        mediaSession = MediaSession.Builder(this, tracks[TrackId.PODCAST]!!.player).build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // MediaSessionService.onBind returns its binder for SERVICE_INTERFACE intents
        // (used by MediaController / system). For plain local binding from the UI,
        // it returns null and we return our LocalBinder instead.
        return super.onBind(intent) ?: binder
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        tracks.values.forEach { it.release() }
        tracks.clear()
        super.onDestroy()
    }
}
