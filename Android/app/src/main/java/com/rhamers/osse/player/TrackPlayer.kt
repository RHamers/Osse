package com.rhamers.osse.player

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.model.TrackState
import com.rhamers.osse.model.toMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import android.content.Context
import kotlinx.coroutines.launch

class TrackPlayer(context: Context, val trackId: TrackId) {

    internal val player: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _state = MutableStateFlow(TrackState())
    val state: StateFlow<TrackState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    isLoading = playbackState == Player.STATE_BUFFERING,
                    durationMs = player.duration.coerceAtLeast(0L)
                )
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _state.value = _state.value.copy(positionMs = newPosition.positionMs)
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

    fun setSource(source: AudioSource) {
        _state.value = _state.value.copy(source = source)
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
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    fun release() {
        scope.cancel()
        player.release()
    }
}
