package com.rhamers.osse.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.model.AudioSource
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
        private val instrumentation = InstrumentationRegistry.getInstrumentation()

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

    @Before
    fun clearTracks() {
        instrumentation.runOnMainSync { binder.clearTracksForTesting() }
        // Clear the DB so autoAdvance() finds an empty queue and doesn't re-trigger play()
        OsseDatabase.get(ApplicationProvider.getApplicationContext()).clearAllTables()
    }

    @Test
    fun serviceBindsAndReturnsLocalBinder() {
        assertNotNull(binder)
    }

    @Test
    fun activeTrackIdsEmptyOnBind() {
        assertEquals(emptyList<TrackId>(), binder.activeTrackIds.value)
    }

    @Test
    fun getTrackReturnsNullBeforeAdding() {
        assertNull(binder.getTrack(TrackId.PODCAST))
        assertNull(binder.getTrack(TrackId.MUSIC))
    }

    @Test
    fun addTrackMakesItRetrievable() {
        instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
        val track = binder.getTrack(TrackId.PODCAST)
        assertNotNull(track)
        assertEquals(TrackId.PODCAST, track!!.trackId)
    }

    @Test
    fun addTrackAppearsInActiveIds() {
        instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
        assertTrue(binder.activeTrackIds.value.contains(TrackId.PODCAST))
        assertFalse(binder.activeTrackIds.value.contains(TrackId.MUSIC))
    }

    @Test
    fun addingBothTracksPopulatesActiveIds() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
        }
        assertEquals(2, binder.activeTrackIds.value.size)
        assertTrue(binder.activeTrackIds.value.containsAll(TrackId.entries))
    }

    @Test
    fun addTrackTwiceReturnsSameInstance() {
        lateinit var first: TrackPlayer
        lateinit var second: TrackPlayer
        instrumentation.runOnMainSync {
            first = binder.addTrack(TrackId.PODCAST)
            second = binder.addTrack(TrackId.PODCAST)
        }
        assertSame(first, second)
        assertEquals(1, binder.activeTrackIds.value.size)
    }

    @Test
    fun addedTrackHasDefaultState() {
        instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
        val state = binder.getTrack(TrackId.PODCAST)!!.state.value
        assertFalse(state.isPlaying)
        assertEquals(1.0f, state.volume, 0.001f)
        assertEquals(1.0f, state.speed, 0.001f)
        assertNull(state.source)
    }

    @Test
    fun volumeChangesOnPodcastDoNotAffectMusic() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
            binder.getTrack(TrackId.PODCAST)!!.setVolume(0.3f)
        }
        assertEquals(0.3f, binder.getTrack(TrackId.PODCAST)!!.state.value.volume, 0.001f)
        assertEquals(1.0f, binder.getTrack(TrackId.MUSIC)!!.state.value.volume, 0.001f)
    }

    @Test
    fun markCurrentEpisodeCompletePausesTrack() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
            }
        }
        instrumentation.runOnMainSync { binder.markCurrentEpisodeComplete() }
        // Allow the IO coroutine to finish and its Main continuation to be posted
        Thread.sleep(300)
        // Drain the main Looper so the continuation (track.pause()) has run
        instrumentation.runOnMainSync {}
        var playWhenReady = true
        instrumentation.runOnMainSync {
            playWhenReady = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
        }
        assertFalse(playWhenReady)
    }

    @Test
    fun speedChangesOnMusicDoNotAffectPodcast() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
            binder.getTrack(TrackId.MUSIC)!!.setSpeed(2.0f)
        }
        assertEquals(2.0f, binder.getTrack(TrackId.MUSIC)!!.state.value.speed, 0.001f)
        assertEquals(1.0f, binder.getTrack(TrackId.PODCAST)!!.state.value.speed, 0.001f)
    }
}
