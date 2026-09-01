package com.rhamers.osse.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.PlaybackStateEntity
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.PlaybackSourceType
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.model.toPlaybackRef
import com.rhamers.osse.player.DefaultYoutubeCacheManager
import com.rhamers.osse.youtube.YoutubeAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MultiTrackService : MediaSessionService() {

    private val tracks = mutableMapOf<TrackId, TrackPlayer>()
    private val persistenceJobs = mutableMapOf<TrackId, Job>()
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

    // Tracks which track IDs have already had their stale playback_state row cleared for
    // their current AudioSource.YoutubeStream source, so persistState() doesn't issue a
    // redundant DELETE on every debounce/flush tick for the entire duration of a
    // YoutubeStream's background download (there's nothing left to delete after the first
    // tick). Cleared per-trackId whenever a persistable source is set, and reset entirely by
    // clearTracksForTesting() so tests stay isolated.
    private val youtubeStreamCleared: MutableSet<TrackId> =
        java.util.Collections.synchronizedSet(mutableSetOf())

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

    private var pendingPressJob: Job? = null

    // Handles the earbud/headset media button. A single press toggles play/pause across
    // every track that has a source loaded; two presses within DOUBLE_PRESS_WINDOW_MS
    // fast-forward the podcast track 30 seconds instead, matching standard podcast-app
    // double-tap-to-skip behavior. This replaces Media3's default handling, which would
    // otherwise forward the event only to the podcast player the session is bound to.
    private val mediaButtonCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val event = keyEventFromIntent(intent) ?: return false
            return handleMediaButtonKeyEvent(event)
        }
    }

    private fun keyEventFromIntent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private fun handleMediaButtonKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            event.keyCode != KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            return false
        }
        // Only react to the initial press; ignore key-up and long-press auto-repeat.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true

        val pending = pendingPressJob
        if (pending != null) {
            pending.cancel()
            pendingPressJob = null
            tracks[TrackId.PODCAST]?.forward()
        } else {
            pendingPressJob = serviceScope.launch {
                delay(DOUBLE_PRESS_WINDOW_MS)
                pendingPressJob = null
                toggleAllActiveTracks()
            }
        }
        return true
    }

    // The OS routes earbud/media-button events to the session whose player is playing;
    // the platform session only mirrors the one player it is bound to. Bound solely to
    // the idle podcast player during music-only playback, the app looks silent to the
    // system and onMediaButtonEvent never fires. Rebind the session to whichever player
    // is actually playing (podcast wins when both are) so button events reach us.
    private fun updateMediaSessionPlayer() {
        val session = mediaSession ?: return
        val podcast = tracks[TrackId.PODCAST]?.player
        val music = tracks[TrackId.MUSIC]?.player
        val target = when {
            podcast != null && podcast.playWhenReady -> podcast
            music != null && music.playWhenReady -> music
            // Nothing playing: keep the current binding so the session retains the
            // paused player's state and stays the most-recent target for routing.
            else -> return
        }
        if (session.player !== target) session.setPlayer(target)
    }

    private fun toggleAllActiveTracks() {
        val active = tracks.values.filter { it.state.value.source != null }
        if (active.any { it.player.playWhenReady }) {
            active.forEach { it.pause() }
        } else {
            active.forEach { it.play() }
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
            player.player.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateMediaSessionPlayer()
                }
            })
            startPlaybackStatePersistence(id, player)
            if (id == TrackId.PODCAST && mediaSession == null) {
                mediaSession = MediaSession.Builder(this@MultiTrackService, player.player)
                    .setCallback(mediaButtonCallback)
                    .build()
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
            persistenceJobs.values.forEach { it.cancel() }
            persistenceJobs.clear()
            tracks.values.forEach { it.release() }
            tracks.clear()
            _activeTrackIds.value = emptyList()
            pausedByFocusLoss.clear()
            youtubeStreamCleared.clear()
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

        fun simulateMediaButtonPress() {
            handleMediaButtonKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }

        fun restorePlaybackStateForTesting() = restorePlaybackState()

        fun sessionPlayerForTesting(): Player? = mediaSession?.player
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
        restorePlaybackState()
        startPeriodicPlaybackFlush()
    }

    // Reloads each track's persisted source/title/position from `playback_state` so
    // playback survives process death. Never calls .play() — restored tracks stay paused
    // until a media-button press (or other explicit user action) resumes them via
    // toggleAllActiveTracks(). Runs synchronously on Dispatchers.IO so onCreate() doesn't
    // return until tracks are loaded, avoiding a window where the UI observes empty tracks.
    private fun restorePlaybackState() {
        val db = OsseDatabase.get(this)

        data class Restored(
            val trackId: TrackId,
            val source: AudioSource,
            val title: String?,
            val positionMs: Long,
            val speed: Float?,
        )

        val toRestore = try {
            runBlocking(Dispatchers.IO) {
                listOf(TrackId.PODCAST, TrackId.MUSIC).mapNotNull { trackId ->
                    val row = db.playbackStateDao().getByTrackId(trackId.name) ?: return@mapNotNull null
                    val source: AudioSource? = when (row.sourceType) {
                        PlaybackSourceType.RSS_FEED -> AudioSource.RssFeed(row.sourceRef)
                        PlaybackSourceType.LOCAL_FILE -> AudioSource.LocalFile(Uri.parse(row.sourceRef))
                        PlaybackSourceType.YOUTUBE -> {
                            val cached = db.youtubeCacheDao().getById(row.sourceRef)
                            if (cached == null) {
                                db.playbackStateDao().delete(trackId.name)
                                null
                            } else {
                                AudioSource.YoutubeDownloaded(cached.localPath)
                            }
                        }
                        else -> {
                            // Unrecognized/corrupt sourceType: nothing we can restore from it,
                            // and it should never be left behind for a future restore attempt
                            // to trip over either.
                            db.playbackStateDao().delete(trackId.name)
                            null
                        }
                    }
                    source ?: return@mapNotNull null
                    val speed = if (trackId == TrackId.PODCAST && source is AudioSource.RssFeed) {
                        db.episodeDao().getByEnclosureUrl(source.enclosureUrl)
                            ?.podcastFeedUrl
                            ?.let { db.podcastDao().getByUrl(it) }
                            ?.defaultSpeed
                    } else {
                        null
                    }
                    Restored(trackId, source, row.title, row.positionMs, speed)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "restorePlaybackState failed", e)
            emptyList()
        }

        toRestore.forEach { r ->
            val track = tracks[r.trackId] ?: return@forEach
            // prepare = false: restoring on a bare service (re)creation — including an
            // unattended OS restart after a low-memory kill — must not start network
            // buffering the user never asked for. TrackPlayer.play() already prepares from
            // STATE_IDLE, so the track prepares correctly the moment playback is actually
            // requested.
            track.setSource(r.source, r.title, prepare = false)
            track.seekTo(r.positionMs)
            r.speed?.let { track.setSpeed(it) }
        }
    }

    // The debounce(2s) collector in startPlaybackStatePersistence() only emits once a track's
    // state goes quiet for 2s. TrackPlayer emits a fresh positionMs into its state StateFlow
    // every 500ms while actually playing, so the upstream never goes quiet during an
    // uninterrupted play session — the debounced collector alone would never persist a
    // currently-playing track's position, leaving it arbitrarily stale for the process-death
    // resume scenario this feature exists for. This periodic flush persists every currently
    // playing track's state on a fixed cadence regardless of whether the value actually
    // changed, independent of the debounce path. It re-reads `tracks` fresh on every tick
    // rather than capturing a TrackPlayer/StateFlow reference once, so it naturally survives
    // this file's clearTracksForTesting() + addTrack() re-creation pattern without needing
    // per-instance wiring.
    private fun startPeriodicPlaybackFlush() {
        val db = OsseDatabase.get(this)
        serviceScope.launch {
            while (true) {
                delay(PERIODIC_FLUSH_INTERVAL_MS)
                // One tick's DAO failure must not crash the root SupervisorJob scope (it has
                // no exception handler) nor permanently kill this loop — log and let the next
                // delay()/tick proceed.
                try {
                    TrackId.entries.forEach { trackId ->
                        val state = tracks[trackId]?.state?.value ?: return@forEach
                        if (state.isPlaying) {
                            persistState(db, trackId, state.source, state.title, state.positionMs)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "periodic playback flush tick failed", e)
                }
            }
        }
    }

    private fun startPlaybackStatePersistence(trackId: TrackId, player: TrackPlayer) {
        val db = OsseDatabase.get(this)
        persistenceJobs[trackId] = serviceScope.launch {
            player.state
                .map { Triple(it.source, it.title, it.positionMs) }
                .distinctUntilChanged()
                .debounce(2_000)
                .collect { (source, title, positionMs) -> persistState(db, trackId, source, title, positionMs) }
        }
    }

    private suspend fun persistState(
        db: OsseDatabase,
        trackId: TrackId,
        source: AudioSource?,
        title: String?,
        positionMs: Long,
    ) {
        withContext(Dispatchers.IO) {
            val ref = source?.toPlaybackRef()
            if (ref == null) {
                if (source is AudioSource.YoutubeStream) {
                    // toPlaybackRef() always returns null for YoutubeStream (its CDN URL is
                    // ephemeral), so without this guard every debounce/flush tick for the
                    // entire duration of its background download would issue a DELETE for
                    // no reason — there's nothing left to delete after the first tick clears
                    // any stale row from a previously persisted source.
                    if (trackId in youtubeStreamCleared) return@withContext
                    youtubeStreamCleared += trackId
                }
                db.playbackStateDao().delete(trackId.name)
            } else {
                youtubeStreamCleared -= trackId
                val (sourceType, sourceRef) = ref
                db.playbackStateDao().upsert(
                    PlaybackStateEntity(
                        trackId = trackId.name,
                        sourceType = sourceType,
                        sourceRef = sourceRef,
                        title = title,
                        positionMs = positionMs,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            if (source is AudioSource.RssFeed) {
                db.episodeDao().getByEnclosureUrl(source.enclosureUrl)?.let { ep ->
                    if (!ep.completed && positionMs > 0) {
                        db.episodeDao().updatePosition(ep.guid, positionMs)
                    }
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

    companion object {
        private const val TAG = "MultiTrackService"
        private const val DOUBLE_PRESS_WINDOW_MS = 300L
        private const val PERIODIC_FLUSH_INTERVAL_MS = 2_000L
    }
}
