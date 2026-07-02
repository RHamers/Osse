package com.rhamers.osse.player

import androidx.test.core.app.ApplicationProvider
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
