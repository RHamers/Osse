package com.rhamers.osse.player

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.rhamers.osse.model.TrackId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiTrackServiceTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private fun binder(): MultiTrackService.LocalBinder {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MultiTrackService::class.java)
        return serviceRule.bindService(intent) as MultiTrackService.LocalBinder
    }

    @Test
    fun serviceBindsAndReturnsLocalBinder() {
        assertNotNull(binder())
    }

    @Test
    fun allTracksCreatedOnBind() {
        val tracks = binder().getAllTracks()
        assertEquals(2, tracks.size)
        assertTrue(tracks.containsKey(TrackId.PODCAST))
        assertTrue(tracks.containsKey(TrackId.MUSIC))
    }

    @Test
    fun podcastTrackHasCorrectId() {
        assertEquals(TrackId.PODCAST, binder().getTrack(TrackId.PODCAST)?.trackId)
    }

    @Test
    fun musicTrackHasCorrectId() {
        assertEquals(TrackId.MUSIC, binder().getTrack(TrackId.MUSIC)?.trackId)
    }

    @Test
    fun tracksHaveDefaultState() {
        val binder = binder()
        for (id in TrackId.entries) {
            val state = binder.getTrack(id)!!.state.value
            assertFalse("track $id should not be playing", state.isPlaying)
            assertEquals("track $id volume default", 1.0f, state.volume, 0.001f)
            assertEquals("track $id speed default", 1.0f, state.speed, 0.001f)
        }
    }

    @Test
    fun volumeChangesOnPodcastDoNotAffectMusic() {
        val binder = binder()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            binder.getTrack(TrackId.PODCAST)!!.setVolume(0.3f)
        }
        assertEquals(0.3f, binder.getTrack(TrackId.PODCAST)!!.state.value.volume, 0.001f)
        assertEquals(1.0f, binder.getTrack(TrackId.MUSIC)!!.state.value.volume, 0.001f)
    }

    @Test
    fun speedChangesOnMusicDoNotAffectPodcast() {
        val binder = binder()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            binder.getTrack(TrackId.MUSIC)!!.setSpeed(2.0f)
        }
        assertEquals(2.0f, binder.getTrack(TrackId.MUSIC)!!.state.value.speed, 0.001f)
        assertEquals(1.0f, binder.getTrack(TrackId.PODCAST)!!.state.value.speed, 0.001f)
    }
}
