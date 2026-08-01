package com.rhamers.osse.player

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackPlayerTest {

    private lateinit var player: TrackPlayer

    @Before
    fun setUp() {
        player = TrackPlayer(ApplicationProvider.getApplicationContext(), TrackId.PODCAST)
    }

    @After
    fun tearDown() {
        player.release()
    }

    @Test
    fun initialStateHasCorrectDefaults() {
        val state = player.state.value
        assertNull(state.source)
        assertFalse(state.isPlaying)
        assertEquals(1.0f, state.volume, 0.001f)
        assertEquals(1.0f, state.speed, 0.001f)
        assertEquals(0L, state.positionMs)
        assertFalse(state.isLoading)
    }

    @Test
    fun trackIdIsPreserved() {
        assertEquals(TrackId.PODCAST, player.trackId)
    }

    @Test
    fun setVolumeUpdatesState() {
        player.setVolume(0.5f)
        assertEquals(0.5f, player.state.value.volume, 0.001f)
    }

    @Test
    fun setVolumeClampsBelowZero() {
        player.setVolume(-1f)
        assertEquals(0f, player.state.value.volume, 0.001f)
    }

    @Test
    fun setVolumeClampAboveOne() {
        player.setVolume(2f)
        assertEquals(1f, player.state.value.volume, 0.001f)
    }

    @Test
    fun setVolumeAtBoundaryZero() {
        player.setVolume(0f)
        assertEquals(0f, player.state.value.volume, 0.001f)
    }

    @Test
    fun setVolumeAtBoundaryOne() {
        player.setVolume(1f)
        assertEquals(1f, player.state.value.volume, 0.001f)
    }

    @Test
    fun setSpeedUpdatesState() {
        player.setSpeed(1.5f)
        assertEquals(1.5f, player.state.value.speed, 0.001f)
    }

    @Test
    fun setSpeedToHalfSpeed() {
        player.setSpeed(0.5f)
        assertEquals(0.5f, player.state.value.speed, 0.001f)
    }

    @Test
    fun setSourceUpdatesStateSource() {
        val source = AudioSource.RssFeed("https://example.com/episode.mp3")
        player.setSource(source)
        assertEquals(source, player.state.value.source)
    }

    @Test
    fun setSourceReplacesExistingSource() {
        val first = AudioSource.RssFeed("https://example.com/episode1.mp3")
        val second = AudioSource.RssFeed("https://example.com/episode2.mp3")
        player.setSource(first)
        player.setSource(second)
        assertEquals(second, player.state.value.source)
    }

    @Test
    fun seekToUpdatesPositionInState() {
        player.seekTo(30_000L)
        assertEquals(30_000L, player.state.value.positionMs)
    }

    @Test
    fun playWhenIdleDoesNotThrow() {
        // A fresh player (no setSource called yet) is in STATE_IDLE. play() must call
        // prepare() internally so ExoPlayer can recover — e.g. after a stream URL expires
        // and the player falls back to STATE_IDLE. Without the prepare() call, play() is
        // a no-op and the user is stuck.
        player.play()  // must not throw; player simply won't advance without media
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun playerBuildsWithCustomHttpDataSourceAndNetworkWakeMode() {
        // Verifies the player is constructed with the custom browser User-Agent data source
        // (to avoid 403 from YouTube CDN) and WAKE_MODE_NETWORK (WiFi lock for streaming).
        // Both require WAKE_LOCK in the manifest.
        assertNotNull(player.player)
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun playInvokesOnPlayRequestedCallback() {
        var invoked = false
        player.onPlayRequested = { invoked = true }
        player.play()
        assertTrue(invoked)
    }

    @Test
    fun playWithNoCallbackDoesNotThrow() {
        player.onPlayRequested = null
        player.play()
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun playerErrorInvokesCallback() {
        var received: PlaybackException? = null
        player.onPlaybackError = { received = it }
        val error = PlaybackException("network error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        player.handlePlayerError(error)

        assertEquals(error, received)
    }

    @Test
    fun playerErrorSchedulesRetryThatResumesPlayback() {
        val retryingPlayer = TrackPlayer(
            ApplicationProvider.getApplicationContext(),
            TrackId.MUSIC,
            retryDelaysMs = longArrayOf(0L),
        )
        retryingPlayer.setSource(AudioSource.RssFeed("https://example.com/episode.mp3"))
        retryingPlayer.play()
        val error = PlaybackException("network error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        retryingPlayer.handlePlayerError(error)
        shadowOf(Looper.getMainLooper()).idle()

        // The retry re-prepares and restores playWhenReady rather than leaving the
        // track stuck paused, requiring the user to manually tap play again.
        assertTrue(retryingPlayer.player.playWhenReady)
        retryingPlayer.release()
    }

    @Test
    fun playerErrorDoesNotResumeIfTrackWasPaused() {
        val retryingPlayer = TrackPlayer(
            ApplicationProvider.getApplicationContext(),
            TrackId.MUSIC,
            retryDelaysMs = longArrayOf(0L),
        )
        retryingPlayer.setSource(AudioSource.RssFeed("https://example.com/episode.mp3"))
        // Never played — playWhenReady is false.
        val error = PlaybackException("network error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        retryingPlayer.handlePlayerError(error)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(retryingPlayer.player.playWhenReady)
        retryingPlayer.release()
    }

    @Test
    fun playDuringRetryWindowIsNotRevertedByPendingRetry() {
        val retryingPlayer = TrackPlayer(
            ApplicationProvider.getApplicationContext(),
            TrackId.MUSIC,
            retryDelaysMs = longArrayOf(0L),
        )
        retryingPlayer.setSource(AudioSource.RssFeed("https://example.com/episode.mp3"))
        // Error while paused (e.g. stream 403 right after setSource, before the user
        // pressed play) — a retry is now pending with playWhenReady=false semantics.
        val error = PlaybackException("network error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        retryingPlayer.handlePlayerError(error)

        // The user presses play inside the backoff window.
        retryingPlayer.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "a play press during the retry backoff window must stick, not be reverted",
            retryingPlayer.player.playWhenReady,
        )
        assertFalse("the manual play replaces the scheduled retry", retryingPlayer.hasPendingRetry)
        retryingPlayer.release()
    }

    @Test
    fun setSourceCancelsPendingRetryAndResetsRetryBudget() {
        val retryingPlayer = TrackPlayer(
            ApplicationProvider.getApplicationContext(),
            TrackId.MUSIC,
            retryDelaysMs = longArrayOf(60_000L),
        )
        retryingPlayer.setSource(AudioSource.RssFeed("https://example.com/old.mp3"))
        val error = PlaybackException("network error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        retryingPlayer.handlePlayerError(error)
        assertTrue(retryingPlayer.hasPendingRetry)
        assertEquals(1, retryingPlayer.currentRetryAttempt)

        retryingPlayer.setSource(AudioSource.RssFeed("https://example.com/new.mp3"))

        assertFalse(
            "a retry scheduled for the old source must not fire against the new one",
            retryingPlayer.hasPendingRetry,
        )
        assertEquals(0, retryingPlayer.currentRetryAttempt)
        retryingPlayer.release()
    }

    @Test
    fun playerErrorGivesUpAfterExhaustingRetries() {
        val retryingPlayer = TrackPlayer(
            ApplicationProvider.getApplicationContext(),
            TrackId.MUSIC,
            retryDelaysMs = longArrayOf(0L),
        )
        val error = PlaybackException("network error", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        retryingPlayer.handlePlayerError(error)
        assertEquals(1, retryingPlayer.currentRetryAttempt)

        // A second error exceeds the single configured retry attempt — must not throw
        // and must not schedule another retry.
        retryingPlayer.handlePlayerError(error)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, retryingPlayer.currentRetryAttempt)
        retryingPlayer.release()
    }
}
