package com.rhamers.osse.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rhamers.osse.model.TrackId
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MultiTrackServiceTest {

    companion object {
        private lateinit var binder: MultiTrackService.LocalBinder
        private var conn: ServiceConnection? = null

        @BeforeClass @JvmStatic
        fun bindService() {
            val latch = CountDownLatch(1)
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    binder = service as MultiTrackService.LocalBinder
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) {}
            }
            conn = connection
            ctx.bindService(Intent(ctx, MultiTrackService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue("Service did not bind within timeout", latch.await(10, TimeUnit.SECONDS))
        }

        @AfterClass @JvmStatic
        fun unbindService() {
            conn?.let { ApplicationProvider.getApplicationContext<Context>().unbindService(it) }
        }
    }

    // Reset track state before each test so state-mutating tests don't pollute each other
    @Before
    fun resetTracks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            binder.getAllTracks().values.forEach { track ->
                track.setVolume(1.0f)
                track.setSpeed(1.0f)
            }
        }
    }

    @Test
    fun serviceBindsAndReturnsLocalBinder() {
        assertNotNull(binder)
    }

    @Test
    fun allTracksCreatedOnBind() {
        val tracks = binder.getAllTracks()
        assertEquals(2, tracks.size)
        assertTrue(tracks.containsKey(TrackId.PODCAST))
        assertTrue(tracks.containsKey(TrackId.MUSIC))
    }

    @Test
    fun podcastTrackHasCorrectId() {
        assertEquals(TrackId.PODCAST, binder.getTrack(TrackId.PODCAST)?.trackId)
    }

    @Test
    fun musicTrackHasCorrectId() {
        assertEquals(TrackId.MUSIC, binder.getTrack(TrackId.MUSIC)?.trackId)
    }

    @Test
    fun tracksHaveDefaultState() {
        for (id in TrackId.entries) {
            val state = binder.getTrack(id)!!.state.value
            assertFalse("track $id should not be playing", state.isPlaying)
            assertEquals("track $id volume default", 1.0f, state.volume, 0.001f)
            assertEquals("track $id speed default", 1.0f, state.speed, 0.001f)
        }
    }

    @Test
    fun volumeChangesOnPodcastDoNotAffectMusic() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            binder.getTrack(TrackId.PODCAST)!!.setVolume(0.3f)
        }
        assertEquals(0.3f, binder.getTrack(TrackId.PODCAST)!!.state.value.volume, 0.001f)
        assertEquals(1.0f, binder.getTrack(TrackId.MUSIC)!!.state.value.volume, 0.001f)
    }

    @Test
    fun speedChangesOnMusicDoNotAffectPodcast() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            binder.getTrack(TrackId.MUSIC)!!.setSpeed(2.0f)
        }
        assertEquals(2.0f, binder.getTrack(TrackId.MUSIC)!!.state.value.speed, 0.001f)
        assertEquals(1.0f, binder.getTrack(TrackId.PODCAST)!!.state.value.speed, 0.001f)
    }
}
