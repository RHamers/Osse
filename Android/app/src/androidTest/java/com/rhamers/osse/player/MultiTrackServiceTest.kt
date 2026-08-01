package com.rhamers.osse.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rhamers.osse.data.db.EpisodeEntity
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.PodcastEntity
import com.rhamers.osse.data.db.QueueItemEntity
import androidx.media3.common.Player
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import kotlinx.coroutines.runBlocking
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
    fun serviceCanBeExplicitlyStartedIndependentOfBinding() {
        // The fixed MainActivity calls startService() in onCreate() so the service
        // survives unbindService() when the activity is backgrounded. Verify startService()
        // returns a valid ComponentName (null would mean the service isn't declared in manifest).
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = ctx.startService(Intent(ctx, MultiTrackService::class.java))
        assertNotNull("startService() must succeed for background audio to work", name)
        assertEquals("com.rhamers.osse", name!!.packageName)
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

    @Test
    fun autoAdvanceKeepsNextEpisodeInQueue() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.podcastDao().upsert(
                PodcastEntity(feedUrl = "https://feed.example.com", title = "Podcast",
                    artworkUrl = null, description = null)
            )
            db.episodeDao().upsertAll(listOf(
                EpisodeEntity(guid = "ep1", podcastFeedUrl = "https://feed.example.com",
                    title = "Episode 1", enclosureUrl = "https://example.com/ep1.mp3",
                    durationMs = 0L, showNotes = null, pubDateMs = 0L),
                EpisodeEntity(guid = "ep2", podcastFeedUrl = "https://feed.example.com",
                    title = "Episode 2", enclosureUrl = "https://example.com/ep2.mp3",
                    durationMs = 0L, showNotes = null, pubDateMs = 0L)
            ))
            db.queueItemDao().insert(QueueItemEntity(episodeGuid = "ep1", position = 0, addedAt = 0L))
            db.queueItemDao().insert(QueueItemEntity(episodeGuid = "ep2", position = 1, addedAt = 0L))
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
                .setSource(AudioSource.RssFeed("https://example.com/ep1.mp3"), "Episode 1")
        }
        instrumentation.runOnMainSync { binder.markCurrentEpisodeComplete() }
        Thread.sleep(500)
        instrumentation.runOnMainSync {}

        val guids = runBlocking { db.queueItemDao().getAllGuids() }
        assertFalse("ep1 should be removed from queue", guids.contains("ep1"))
        assertTrue("ep2 should still be in queue", guids.contains("ep2"))
    }

    @Test
    fun audioBecomingNoisyPausesPodcastTrack() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
            }
            binder.simulateBecomingNoisy()
        }
        var playWhenReady = true
        instrumentation.runOnMainSync {
            playWhenReady = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
        }
        assertFalse("podcast track should pause when audio becomes noisy", playWhenReady)
    }

    @Test
    fun audioBecomingNoisyPausesMusicTrack() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
                play()
            }
            binder.simulateBecomingNoisy()
        }
        var playWhenReady = true
        instrumentation.runOnMainSync {
            playWhenReady = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertFalse("music track should pause when audio becomes noisy", playWhenReady)
    }

    @Test
    fun audioFocusTransientLossPausesPlayingTrack() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
            }
            binder.simulateAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        }
        var playWhenReady = true
        instrumentation.runOnMainSync {
            playWhenReady = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
        }
        assertFalse("podcast should pause on transient audio focus loss", playWhenReady)
    }

    @Test
    fun audioFocusGainAfterTransientLossResumesPlayback() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
            }
            binder.simulateAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            binder.simulateAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        var playWhenReady = false
        instrumentation.runOnMainSync {
            playWhenReady = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
        }
        assertTrue("podcast should resume when focus returns after transient loss", playWhenReady)
    }

    @Test
    fun audioFocusPermanentLossDoesNotAutoResume() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
            }
            binder.simulateAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            binder.simulateAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        var playWhenReady = false
        instrumentation.runOnMainSync {
            playWhenReady = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
        }
        assertFalse("podcast should not auto-resume after permanent focus loss", playWhenReady)
    }

    @Test
    fun audioFocusTransientLossOnlyResumesTracksThatWerePlaying() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                // deliberately NOT calling play() — user had it paused
            }
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
                play()
            }
            binder.simulateAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            binder.simulateAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        var podcastPlaying = false
        var musicPlaying = false
        instrumentation.runOnMainSync {
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
            musicPlaying  = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertFalse("podcast was paused before focus loss — should stay paused", podcastPlaying)
        assertTrue("music was playing before focus loss — should resume", musicPlaying)
    }

    @Test
    fun markCompleteMarksEpisodeCompletedInDb() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.podcastDao().upsert(
                PodcastEntity(feedUrl = "https://feed.example.com", title = "Podcast",
                    artworkUrl = null, description = null)
            )
            db.episodeDao().upsertAll(listOf(
                EpisodeEntity(guid = "ep1", podcastFeedUrl = "https://feed.example.com",
                    title = "Episode 1", enclosureUrl = "https://example.com/ep1.mp3",
                    durationMs = 0L, showNotes = null, pubDateMs = 0L)
            ))
            db.queueItemDao().insert(QueueItemEntity(episodeGuid = "ep1", position = 0, addedAt = 0L))
        }
        val itemId = runBlocking { db.queueItemDao().peekFirst()!!.id }
        instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
        instrumentation.runOnMainSync { binder.markComplete(itemId, "https://example.com/ep1.mp3") }
        Thread.sleep(300)
        instrumentation.runOnMainSync {}

        val episode = runBlocking { db.episodeDao().getByGuid("ep1") }
        assertTrue("episode should be marked completed in DB", episode!!.completed)
    }

    @Test
    fun singleMediaButtonPressPlaysBothLoadedIdleTracks() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
            binder.addTrack(TrackId.MUSIC).setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
            binder.simulateMediaButtonPress()
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync {}
        var podcastPlaying = false
        var musicPlaying = false
        instrumentation.runOnMainSync {
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertTrue("podcast should start playing on single press", podcastPlaying)
        assertTrue("music should start playing on single press", musicPlaying)
    }

    @Test
    fun singleMediaButtonPressPausesAllActiveTracksWhenAnyIsPlaying() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
            }
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
                // deliberately not playing — user paused music manually
            }
            binder.simulateMediaButtonPress()
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync {}
        var podcastPlaying = true
        var musicPlaying = true
        instrumentation.runOnMainSync {
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertFalse(
            "podcast should pause too on single press, even though music was already paused",
            podcastPlaying
        )
        assertFalse("music should remain paused, not flip to playing", musicPlaying)
    }

    @Test
    fun doubleMediaButtonPressSeeksPodcastForward30SecondsWithoutTogglingPlayback() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
                seekTo(10_000L)
            }
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
                play()
            }
            binder.simulateMediaButtonPress()
            binder.simulateMediaButtonPress()
        }
        var podcastPosition = 0L
        var podcastPlaying = false
        var musicPlaying = false
        instrumentation.runOnMainSync {
            podcastPosition = binder.getTrack(TrackId.PODCAST)!!.state.value.positionMs
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertEquals(40_000L, podcastPosition)
        assertTrue("podcast should keep playing, only its position should change", podcastPlaying)
        assertTrue("music should be unaffected by a double press", musicPlaying)
    }

    @Test
    fun mediaSessionBindsToPodcastPlayerByDefault() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
        }
        var sessionPlayer: Player? = null
        var podcastPlayer: Player? = null
        instrumentation.runOnMainSync {
            sessionPlayer = binder.sessionPlayerForTesting()
            podcastPlayer = binder.getTrack(TrackId.PODCAST)!!.player
        }
        assertSame("session should be bound to the podcast player initially", podcastPlayer, sessionPlayer)
    }

    @Test
    fun mediaSessionRebindsToMusicPlayerDuringMusicOnlyPlayback() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.YoutubeStream("https://youtube.com/watch?v=abc"))
                play()
            }
        }
        instrumentation.waitForIdleSync()
        var sessionPlayer: Player? = null
        var musicPlayer: Player? = null
        instrumentation.runOnMainSync {
            sessionPlayer = binder.sessionPlayerForTesting()
            musicPlayer = binder.getTrack(TrackId.MUSIC)!!.player
        }
        assertSame(
            "session must mirror the music player when only music plays, or the OS " +
                "never routes earbud button events to the app",
            musicPlayer, sessionPlayer
        )
    }

    @Test
    fun mediaSessionRebindsToPodcastPlayerWhenPodcastStartsPlaying() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.YoutubeStream("https://youtube.com/watch?v=abc"))
                play()
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { binder.getTrack(TrackId.PODCAST)!!.play() }
        instrumentation.waitForIdleSync()
        var sessionPlayer: Player? = null
        var podcastPlayer: Player? = null
        instrumentation.runOnMainSync {
            sessionPlayer = binder.sessionPlayerForTesting()
            podcastPlayer = binder.getTrack(TrackId.PODCAST)!!.player
        }
        assertSame("podcast takes the session back when it starts playing", podcastPlayer, sessionPlayer)
    }

    @Test
    fun mediaSessionKeepsMusicPlayerBindingWhileMusicIsPaused() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.YoutubeStream("https://youtube.com/watch?v=abc"))
                play()
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { binder.getTrack(TrackId.MUSIC)!!.pause() }
        instrumentation.waitForIdleSync()
        var sessionPlayer: Player? = null
        var musicPlayer: Player? = null
        instrumentation.runOnMainSync {
            sessionPlayer = binder.sessionPlayerForTesting()
            musicPlayer = binder.getTrack(TrackId.MUSIC)!!.player
        }
        assertSame(
            "pausing music should not rebind the session — the paused player keeps " +
                "the session current so the next button press still reaches the app",
            musicPlayer, sessionPlayer
        )
    }

    @Test
    fun singleMediaButtonPressWithNoSourceLoadedDoesNotCrashOrPlay() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
            binder.simulateMediaButtonPress()
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync {}
        var podcastPlaying = true
        var musicPlaying = true
        instrumentation.runOnMainSync {
            podcastPlaying = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertFalse("no source loaded — podcast should not start playing", podcastPlaying)
        assertFalse("no source loaded — music should not start playing", musicPlaying)
    }
}
