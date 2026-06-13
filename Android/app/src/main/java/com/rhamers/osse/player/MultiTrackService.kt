package com.rhamers.osse.player

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MultiTrackService : MediaSessionService() {

    private val tracks = mutableMapOf<TrackId, TrackPlayer>()
    private var mediaSession: MediaSession? = null
    private val _activeTrackIds = MutableStateFlow<List<TrackId>>(emptyList())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class LocalBinder : Binder() {
        val activeTrackIds: StateFlow<List<TrackId>> = _activeTrackIds.asStateFlow()

        fun getTrack(id: TrackId): TrackPlayer? = tracks[id]

        fun addTrack(id: TrackId): TrackPlayer {
            tracks[id]?.let { return it }
            val player = TrackPlayer(this@MultiTrackService, id)
            tracks[id] = player
            _activeTrackIds.value = _activeTrackIds.value + id
            if (id == TrackId.PODCAST && mediaSession == null) {
                mediaSession = MediaSession.Builder(this@MultiTrackService, player.player).build()
                player.onEnded = { serviceScope.launch { autoAdvance() } }
            }
            return player
        }

        fun getAllTracks(): Map<TrackId, TrackPlayer> = tracks.toMap()

        fun markCurrentEpisodeComplete() = markEpisodeComplete(TrackId.PODCAST)

        fun markEpisodeComplete(id: TrackId) {
            serviceScope.launch {
                val track = tracks[id] ?: return@launch
                if (track.state.value.source !is AudioSource.RssFeed) return@launch
                track.pause()
                if (id == TrackId.PODCAST) autoAdvance()
            }
        }

        fun markComplete(itemId: Long, enclosureUrl: String) {
            serviceScope.launch {
                withContext(Dispatchers.IO) {
                    val db = OsseDatabase.get(this@MultiTrackService)
                    db.episodeDao().getByEnclosureUrl(enclosureUrl)?.let { ep ->
                        db.episodeDao().setCompleted(ep.guid, true)
                    }
                    db.queueItemDao().deleteById(itemId)
                }
                val currentUrl = (tracks[TrackId.PODCAST]?.state?.value?.source as? AudioSource.RssFeed)?.enclosureUrl
                if (currentUrl == enclosureUrl) {
                    tracks[TrackId.PODCAST]?.pause()
                    autoAdvance()
                }
            }
        }

        fun clearTracksForTesting() {
            mediaSession?.release()
            mediaSession = null
            tracks.values.forEach { it.release() }
            tracks.clear()
            _activeTrackIds.value = emptyList()
        }
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        binder.addTrack(TrackId.PODCAST)
        binder.addTrack(TrackId.MUSIC)
    }

    override fun onBind(intent: Intent?): IBinder? {
        // MediaSessionService.onBind returns its binder for SERVICE_INTERFACE intents
        // (used by MediaController / system). For plain local binding from the UI,
        // it returns null and we return our LocalBinder instead.
        return super.onBind(intent) ?: binder
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        tracks.values.forEach { it.release() }
        tracks.clear()
        super.onDestroy()
    }

    private suspend fun autoAdvance() {
        val db = OsseDatabase.get(this)
        val track = tracks[TrackId.PODCAST] ?: return

        // Mark the episode that just ended/was completed as done and remove from queue.
        // No-op if it was already handled (e.g. by a prior markEpisodeComplete call).
        val currentUrl = (track.state.value.source as? AudioSource.RssFeed)?.enclosureUrl
        if (currentUrl != null) {
            withContext(Dispatchers.IO) {
                db.episodeDao().getByEnclosureUrl(currentUrl)?.let { ep ->
                    db.episodeDao().setCompleted(ep.guid, true)
                    db.queueItemDao().deleteByGuid(ep.guid)
                }
            }
        }

        // Start the next episode without removing it from the queue.
        val episode = withContext(Dispatchers.IO) {
            val item = db.queueItemDao().peekFirst() ?: return@withContext null
            val ep = db.episodeDao().getByGuid(item.episodeGuid)
            if (ep == null) { db.queueItemDao().delete(item); null } else ep
        } ?: return

        track.setSource(AudioSource.RssFeed(episode.enclosureUrl), episode.title)
        track.play()
    }
}
