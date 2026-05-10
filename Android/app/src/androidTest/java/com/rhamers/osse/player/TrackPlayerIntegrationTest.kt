package com.rhamers.osse.player

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackPlayerIntegrationTest {

    companion object {
        private lateinit var player: TrackPlayer
        private val instrumentation = InstrumentationRegistry.getInstrumentation()

        @BeforeClass @JvmStatic
        fun setUpClass() {
            instrumentation.runOnMainSync {
                player = TrackPlayer(ApplicationProvider.getApplicationContext(), TrackId.PODCAST)
            }
        }

        @AfterClass @JvmStatic
        fun tearDownClass() {
            instrumentation.runOnMainSync { player.release() }
        }
    }

    @Before
    fun resetState() {
        instrumentation.runOnMainSync {
            player.player.stop()
            player.player.clearMediaItems()
            player.setVolume(1.0f)
            player.setSpeed(1.0f)
        }
    }

    @Test
    fun playerInitializesWithCorrectDefaults() {
        val state = player.state.value
        assertFalse(state.isPlaying)
        assertEquals(1.0f, state.volume, 0.001f)
        assertEquals(1.0f, state.speed, 0.001f)
        assertEquals(0L, state.positionMs)
        assertFalse(state.isLoading)
    }

    @Test
    fun setVolumeUpdatesStateAndPlayer() {
        var exoVolume = -1f
        instrumentation.runOnMainSync {
            player.setVolume(0.5f)
            exoVolume = player.player.volume
        }
        assertEquals(0.5f, player.state.value.volume, 0.001f)
        assertEquals(0.5f, exoVolume, 0.001f)
    }

    @Test
    fun setVolumeClampsBelowZero() {
        var exoVolume = -1f
        instrumentation.runOnMainSync {
            player.setVolume(-1f)
            exoVolume = player.player.volume
        }
        assertEquals(0f, player.state.value.volume, 0.001f)
        assertEquals(0f, exoVolume, 0.001f)
    }

    @Test
    fun setVolumeClampAboveOne() {
        var exoVolume = -1f
        instrumentation.runOnMainSync {
            player.setVolume(2f)
            exoVolume = player.player.volume
        }
        assertEquals(1f, player.state.value.volume, 0.001f)
        assertEquals(1f, exoVolume, 0.001f)
    }

    @Test
    fun setSpeedUpdatesStateAndPlayer() {
        var exoSpeed = -1f
        instrumentation.runOnMainSync {
            player.setSpeed(1.5f)
            exoSpeed = player.player.playbackParameters.speed
        }
        assertEquals(1.5f, player.state.value.speed, 0.001f)
        assertEquals(1.5f, exoSpeed, 0.001f)
    }

    @Test
    fun setSourceUpdatesState() {
        val source = AudioSource.RssFeed("https://example.com/episode.mp3")
        instrumentation.runOnMainSync { player.setSource(source) }
        assertEquals(source, player.state.value.source)
    }
}
