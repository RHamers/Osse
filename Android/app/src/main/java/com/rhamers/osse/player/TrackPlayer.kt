package com.rhamers.osse.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.model.TrackState
import com.rhamers.osse.model.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TrackPlayer(
    context: Context,
    val trackId: TrackId,
    private val retryDelaysMs: LongArray = DEFAULT_RETRY_DELAYS_MS,
) {

    internal val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            // YouTube CDN returns 403 when ExoPlayer's default "ExoPlayer/..." User-Agent
            // is used. A browser-like UA is accepted by the CDN for the signed URLs that
            // kkdai/youtube extracts.
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    DefaultHttpDataSource.Factory()
                        .setUserAgent(
                            "Mozilla/5.0 (Linux; Android 14) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Mobile Safari/537.36"
                        )
                )
            )
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus= */ false
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    var onEnded: (() -> Unit)? = null
    var onPlayRequested: (() -> Unit)? = null
    var onPlaybackError: ((PlaybackException) -> Unit)? = null

    private val _state = MutableStateFlow(TrackState())
    val state: StateFlow<TrackState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var retryAttempt = 0
    internal val currentRetryAttempt: Int get() = retryAttempt

    private var pendingRetryJob: Job? = null
    internal val hasPendingRetry: Boolean get() = pendingRetryJob?.isActive == true

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING ->
                        Log.d(TAG, "Track $trackId buffering at position=${player.currentPosition}ms")
                    Player.STATE_READY -> {
                        if (retryAttempt > 0) {
                            Log.i(TAG, "Track $trackId recovered after $retryAttempt retry attempt(s)")
                        }
                        retryAttempt = 0
                    }
                    else -> Unit
                }
                _state.value = _state.value.copy(
                    isLoading = playbackState == Player.STATE_BUFFERING,
                    durationMs = player.duration.coerceAtLeast(0L)
                )
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _state.value = _state.value.copy(positionMs = newPosition.positionMs)
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlayerError(error)
            }
        })

        // Emit position updates every 500ms while playing, for progress bar tracking
        scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    _state.value = _state.value.copy(positionMs = player.currentPosition)
                }
                delay(500L)
            }
        }
    }

    fun setSource(source: AudioSource, title: String? = null) {
        // A retry scheduled for the old source must not fire against the new one,
        // and a fresh source deserves a fresh retry budget.
        pendingRetryJob?.cancel()
        pendingRetryJob = null
        retryAttempt = 0
        _state.value = _state.value.copy(source = source, title = title)
        player.setMediaItem(source.toMediaItem())
        player.prepare()
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        player.volume = clamped
        _state.value = _state.value.copy(volume = clamped)
    }

    fun setSpeed(speed: Float) {
        // PlaybackParameters uses ExoPlayer's SonicAudioProcessor internally,
        // which applies pitch correction — no chipmunk effect at high speeds
        player.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun play() {
        // An explicit play already re-prepares below; letting a scheduled retry fire
        // afterwards would seek back to the error position and restart buffering.
        pendingRetryJob?.cancel()
        pendingRetryJob = null
        onPlayRequested?.invoke()
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekTo(0)
            else -> Unit
        }
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    fun rewind() {
        seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
    }

    fun forward() {
        val target = player.currentPosition + 30_000L
        val duration = player.duration
        seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }

    fun release() {
        scope.cancel()
        player.release()
    }

    // YouTube CDN streams have been observed to drop mid-playback (a hard pause every
    // few minutes), surfaced by ExoPlayer as a PlaybackException that falls back to
    // STATE_IDLE. Without recovery, the user has to manually tap play again. Retry with
    // backoff from the last known position before giving up.
    internal fun handlePlayerError(error: PlaybackException) {
        Log.w(TAG, "Track $trackId playback error (source=${_state.value.source}): ${error.errorCodeName}", error)
        onPlaybackError?.invoke(error)

        if (retryAttempt >= retryDelaysMs.size) {
            Log.w(TAG, "Track $trackId giving up after $retryAttempt retries")
            return
        }
        val delayMs = retryDelaysMs[retryAttempt]
        retryAttempt++
        val resumePosition = player.currentPosition
        pendingRetryJob = scope.launch {
            delay(delayMs)
            // playWhenReady survives errors and reflects the user's latest intent —
            // possibly changed since the error was captured. Re-preparing is enough:
            // playback resumes iff it is still true. Writing a value captured at error
            // time would revert a play/pause press made during the backoff window.
            player.seekTo(resumePosition)
            player.prepare()
        }
    }

    companion object {
        private const val TAG = "TrackPlayer"
        private val DEFAULT_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)
    }
}
