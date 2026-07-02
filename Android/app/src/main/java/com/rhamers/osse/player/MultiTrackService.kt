package com.rhamers.osse.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.player.DefaultYoutubeCacheManager
import com.rhamers.osse.youtube.YoutubeAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private lateinit var cacheManager: YoutubeCacheManager

    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // Tracks which track IDs were playing when a transient focus loss occurred,
    // so we only resume those (not tracks the user had manually paused).
    private val pausedByFocusLoss = mutableSetOf<TrackId>()

    // Holds the pending AudioFocusRequest on API 26+ so we can abandon it later.
    private var audioFocusRequestO: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss (e.g. another music app takes over): pause everything
                // and do NOT auto-resume — the user needs to press play again.
                tracks.values.forEach { it.pause() }
                pausedByFocusLoss.clear()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Transient loss (phone call, navigation prompt, BT connection sound):
                // pause whichever tracks are currently playing and remember them so we
                // can resume when focus returns.
                tracks.entries
                    .filter { (_, player) -> player.player.playWhenReady }
                    .forEach { (id, player) ->
                        player.pause()
                        pausedByFocusLoss += id
                    }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus returned after a transient loss — resume the tracks we paused.
                pausedByFocusLoss.forEach { id -> tracks[id]?.play() }
                pausedByFocusLoss.clear()
            }
        }
    }

    // Pauses all tracks when headphones are unplugged or a BT device disconnects,
    // preventing audio from blasting through the speaker unexpectedly.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                tracks.values.forEach { it.pause() }
            }
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequestO == null) {
                audioFocusRequestO = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(audioFocusListener, Handler(mainLooper))
                    .build()
            }
            audioManager.requestAudioFocus(audioFocusRequestO!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequestO?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    inner class LocalBinder : Binder() {
        val activeTrackIds: StateFlow<List<TrackId>> = _activeTrackIds.asStateFlow()
        val cacheManager: YoutubeCacheManager get() = this@MultiTrackService.cacheManager

        fun getTrack(id: TrackId): TrackPlayer? = tracks[id]

        fun addTrack(id: TrackId): TrackPlayer {
            tracks[id]?.let { return it }
            val player = TrackPlayer(this@MultiTrackService, id)
            tracks[id] = player
            _activeTrackIds.value = _activeTrackIds.value + id
            player.onPlayRequested = { requestAudioFocus() }
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
                        db.episodeDao().updatePosition(ep.guid, 0L)
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
            pausedByFocusLoss.clear()
        }

        fun simulateAudioFocusChange(focusChange: Int) {
            audioFocusListener.onAudioFocusChange(focusChange)
        }

        fun simulateBecomingNoisy() {
            noisyReceiver.onReceive(
                this@MultiTrackService,
                Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            )
        }
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        cacheManager = DefaultYoutubeCacheManager(
            extractor = YoutubeAudio,
            cacheDir = (getExternalFilesDir("youtube") ?: filesDir).also { it.mkdirs() },
            db = OsseDatabase.get(this),
            scope = serviceScope,
            onCached = { streamUrl, localPath ->
                // Background download finished: swap any track still streaming from the
                // now-expiring CDN URL to the permanent local file, preserving position.
                tracks.values.forEach { track ->
                    val state = track.state.value
                    val src = state.source
                    if (src is AudioSource.YoutubeStream && src.youtubeUrl == streamUrl) {
                        val position = state.positionMs
                        val wasPlaying = state.isPlaying
                        track.setSource(AudioSource.YoutubeDownloaded(localPath), state.title)
                        if (position > 0) track.seekTo(position)
                        if (wasPlaying) track.play()
                    }
                }
            },
        )
        binder.addTrack(TrackId.PODCAST)
        binder.addTrack(TrackId.MUSIC)
        startPositionSaver()
    }

    private fun startPositionSaver() {
        serviceScope.launch {
            val db = OsseDatabase.get(this@MultiTrackService)
            while (true) {
                delay(10_000)
                val track = tracks[TrackId.PODCAST] ?: continue
                val state = track.state.value
                if (!state.isPlaying) continue
                val url = (state.source as? AudioSource.RssFeed)?.enclosureUrl ?: continue
                if (state.positionMs <= 0) continue
                withContext(Dispatchers.IO) {
                    val entity = db.episodeDao().getByEnclosureUrl(url) ?: return@withContext
                    if (!entity.completed) db.episodeDao().updatePosition(entity.guid, state.positionMs)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // MediaSessionService.onBind returns its binder for SERVICE_INTERFACE intents
        // (used by MediaController / system). For plain local binding from the UI,
        // it returns null and we return our LocalBinder instead.
        return super.onBind(intent) ?: binder
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        abandonAudioFocus()
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
                    db.episodeDao().updatePosition(ep.guid, 0L)
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
