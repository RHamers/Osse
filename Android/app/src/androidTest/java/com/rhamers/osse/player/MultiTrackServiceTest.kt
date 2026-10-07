package com.rhamers.osse.player

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.net.Uri
import android.os.IBinder
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rhamers.osse.data.db.EpisodeEntity
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.PlaybackStateEntity
import com.rhamers.osse.data.db.PodcastEntity
import com.rhamers.osse.data.db.QueueItemEntity
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
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
            // Must happen before bindService() below triggers MultiTrackService.onCreate()
            // (the first OsseDatabase.get() call), otherwise this suite's clearAllTables()
            // calls wipe the real device's production database instead of an isolated one.
            OsseDatabase.useIsolatedTestDatabase(ctx)
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
        assertEquals(ctx.packageName, name!!.packageName)
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
        // Poll rather than a fixed Thread.sleep(300): markEpisodeComplete() finishes an IO
        // coroutine before posting its Main continuation (track.pause()), and a fixed sleep
        // flakes under load (e.g. running alongside the rest of the suite) when that
        // round-trip takes longer than the sleep.
        assertTrue(
            "expected track to be paused after markCurrentEpisodeComplete",
            waitUntilPaused(TrackId.PODCAST, timeoutMs = 3_000),
        )
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
    fun fastForwardKeyEventSeeksPodcastForward30SecondsWithoutTogglingPlayback() {
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
            binder.simulateMediaButtonFastForward()
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
        assertTrue("music should be unaffected by the notification's skip-forward action", musicPlaying)
    }

    @Test
    fun fastForwardKeyEventIsNoOpWhenNoPodcastTrackLoaded() {
        // Guards against a crash if the notification's skip-forward action fires after
        // clearTracksForTesting() wipes the tracks map (or, in production, before onCreate's
        // addTrack(PODCAST) has run). runOnMainSync propagates any exception as a test failure.
        instrumentation.runOnMainSync { binder.simulateMediaButtonFastForward() }
    }

    @Test
    fun notificationShowsReadyWithAppNameWhenNoTrackIsActive() {
        lateinit var notification: Notification
        instrumentation.runOnMainSync { notification = binder.buildNotificationForTesting() }
        assertEquals("Osse", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Ready", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun notificationShowsActiveTrackTitleAndPlayingState() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"), "Episode 42")
                play()
            }
        }
        lateinit var notification: Notification
        instrumentation.runOnMainSync { notification = binder.buildNotificationForTesting() }
        assertEquals("Episode 42", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Playing", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun notificationShowsPausedStateForALoadedButPausedTrack() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
                .setSource(AudioSource.RssFeed("https://example.com/ep.mp3"), "Episode 42")
        }
        lateinit var notification: Notification
        instrumentation.runOnMainSync { notification = binder.buildNotificationForTesting() }
        assertEquals("Paused", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun notificationHasPlayPauseAndSkipForwardActions() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"), "Episode 42")
                play()
            }
        }
        lateinit var notification: Notification
        instrumentation.runOnMainSync { notification = binder.buildNotificationForTesting() }
        assertEquals(2, notification.actions.size)
        assertEquals("Pause", notification.actions[0].title.toString())
        assertEquals("+30s", notification.actions[1].title.toString())
    }

    @Test
    fun clearTracksForTestingDropsAForegroundServiceLeftByAPlayingTrack() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
                play()
            }
        }
        assertTrue(
            "test setup: playing a track must enter foreground",
            waitUntilInForeground(true, timeoutMs = 2_000),
        )
        instrumentation.runOnMainSync { binder.clearTracksForTesting() }
        assertFalse(
            "clearTracksForTesting must leave the service out of foreground, or it poisons " +
                "every later test in this class that assumes a clean starting state",
            binder.isInForegroundForTesting(),
        )
    }

    @Test
    fun coldStartViaMediaButtonCallsStartForegroundEvenWithNothingToResume() {
        // clearTracks() in @Before already wiped the tracks map and the DB, simulating a
        // process wake with nothing restorable to play. A real MediaButtonReceiver cold
        // start forwards the original ACTION_MEDIA_BUTTON intent (action untouched, just
        // re-targeted) to this service via startForegroundService() -- see
        // MultiTrackService.onStartCommand's kdoc for why that specifically requires a
        // prompt startForeground() call regardless of whether anything ends up playing.
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClass(ctx, MultiTrackService::class.java)
            .putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
        ctx.startService(intent)

        assertTrue(
            "startForeground must be called within the OS timeout for a " +
                "MediaButtonReceiver-style cold start",
            waitUntilInForeground(true, timeoutMs = 2_000),
        )
        assertTrue(
            "must reconcile back out of foreground once settling finds nothing playing",
            waitUntilInForeground(false, timeoutMs = 3_000),
        )
    }

    @Test
    fun playerCommandRequestPlayPauseTogglesTracksWhenNoRecentMediaButtonEvent() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
            binder.addTrack(TrackId.MUSIC).setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
            binder.simulatePlayerCommandRequest(Player.COMMAND_PLAY_PAUSE)
        }
        // Poll rather than a fixed Thread.sleep(400) — see the echo-window test below for why.
        assertTrue(
            "podcast should start playing from a bare command request",
            waitUntilPlayWhenReady(TrackId.PODCAST, timeoutMs = 3_000),
        )
        var musicPlaying = false
        instrumentation.runOnMainSync {
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertTrue("music should start playing from a bare command request", musicPlaying)
    }

    @Test
    fun playerCommandRequestPlayPauseIsSkippedAsEchoOfRecentMediaButtonEvent() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
            binder.addTrack(TrackId.MUSIC).setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
            // A real Bluetooth earbud press fires onMediaButtonEvent and this command
            // request within milliseconds of each other for the very same physical press.
            binder.simulateMediaButtonPress()
            binder.simulatePlayerCommandRequest(Player.COMMAND_PLAY_PAUSE)
        }
        // Poll rather than a fixed Thread.sleep(400): the single-press handler's toggle
        // fires after DOUBLE_PRESS_WINDOW_MS (300ms) via a coroutine delay(), which can
        // overrun a fixed sleep under system load (e.g. running alongside the rest of
        // the suite), flaking this assertion without any real bug.
        assertTrue(
            "podcast should play from a single logical press, not be treated as a double press",
            waitUntilPlayWhenReady(TrackId.PODCAST, timeoutMs = 3_000),
        )
        var podcastPosition = 0L
        var musicPlaying = false
        instrumentation.runOnMainSync {
            podcastPosition = binder.getTrack(TrackId.PODCAST)!!.state.value.positionMs
            musicPlaying = binder.getTrack(TrackId.MUSIC)!!.player.playWhenReady
        }
        assertTrue("music should play too, from the same single logical press", musicPlaying)
        assertEquals("the echoed command request must not seek/forward as a double press", 0L, podcastPosition)
    }

    @Test
    fun playerCommandRequestPlayPauseReturnsSkippedResult() {
        var result = SessionResult.RESULT_SUCCESS
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            result = binder.simulatePlayerCommandRequest(Player.COMMAND_PLAY_PAUSE)
        }
        assertEquals(SessionResult.RESULT_INFO_SKIPPED, result)
    }

    @Test
    fun playerCommandRequestNonPlayPauseCommandPassesThrough() {
        var result = SessionResult.RESULT_ERROR_UNKNOWN
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            result = binder.simulatePlayerCommandRequest(Player.COMMAND_SEEK_FORWARD)
        }
        assertEquals(SessionResult.RESULT_SUCCESS, result)
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

    @Test
    fun playbackStateIsPersistedAfterDebounceWindow() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep.mp3"), "Episode 1")
                seekTo(5_000L)
            }
        }
        Thread.sleep(2_500) // > 2s debounce window
        val row = runBlocking { db.playbackStateDao().getByTrackId("PODCAST") }
        assertNotNull("expected a persisted playback_state row for PODCAST", row)
        assertEquals("RSS_FEED", row!!.sourceType)
        assertEquals("https://example.com/ep.mp3", row.sourceRef)
        assertEquals("Episode 1", row.title)
        assertEquals(5_000L, row.positionMs)
    }

    @Test
    fun rssPodcastPositionIsStillWrittenToEpisodesTableAfterDebounce() {
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
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.RssFeed("https://example.com/ep1.mp3"), "Episode 1")
                seekTo(12_000L)
            }
        }
        Thread.sleep(2_500)
        val episode = runBlocking { db.episodeDao().getByGuid("ep1") }
        assertEquals(12_000L, episode!!.positionMs)
    }

    @Test
    fun youtubeStreamSourceIsNotPersisted() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.MUSIC).apply {
                setSource(AudioSource.YoutubeStream("https://youtube.com/watch?v=abc"))
                seekTo(3_000L)
            }
        }
        Thread.sleep(2_500)
        val row = runBlocking { db.playbackStateDao().getByTrackId("MUSIC") }
        assertNull("an ephemeral YouTube CDN stream URL must never be persisted", row)
    }

    // Regression test for the plan-level gap flagged in review: TrackPlayer emits a fresh
    // positionMs into its state StateFlow every 500ms while actually playing, so the
    // debounce(2s) collector's upstream never goes quiet during continuous playback and never
    // fires. This is the exact scenario the resume-after-process-death feature exists for
    // (process killed mid-listen, not mid-pause), so a persisted row must appear — and its
    // position must keep advancing — purely from the periodic flush, with the track never
    // pausing. Uses a real locally-generated silent WAV file (not a fake/unreachable URL like
    // the other tests here) because this is the one test in the file that needs ExoPlayer's
    // actual isPlaying flag to go true, not just playWhenReady.
    @Test
    fun playbackStateAdvancesFromPeriodicFlushDuringContinuousPlayback() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        val wavFile = createSilentWavFile(durationSeconds = 8)
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST).apply {
                setSource(AudioSource.LocalFile(Uri.fromFile(wavFile)))
                play()
            }
        }
        assertTrue(
            "expected the local WAV file to actually start playing (ExoPlayer.isPlaying)",
            waitUntilPlaying(TrackId.PODCAST, timeoutMs = 5_000)
        )

        // Longer than one periodic-flush tick (2s), with margin, never pausing in between.
        Thread.sleep(3_200)

        var isPlaying = false
        instrumentation.runOnMainSync {
            isPlaying = binder.getTrack(TrackId.PODCAST)!!.state.value.isPlaying
        }
        assertTrue(
            "track must still be playing at assertion time — this test proves persistence " +
                "happens DURING play, not only after pause/source-change",
            isPlaying
        )

        val row = runBlocking { db.playbackStateDao().getByTrackId("PODCAST") }
        assertNotNull(
            "the periodic flush should have persisted a playback_state row while the track " +
                "was continuously playing (the debounce collector alone never fires here, " +
                "since positionMs never stops changing while playing)",
            row
        )
        assertTrue(
            "positionMs should have advanced past 0 from the periodic flush, with the track " +
                "never pausing",
            row!!.positionMs > 0L
        )
    }

    @Test
    fun restoringPlaybackStateReloadsSourceTitleAndPosition() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/ep.mp3", title = "Restored Episode",
                    positionMs = 42_000L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.restorePlaybackStateForTesting()
        }
        val state = binder.getTrack(TrackId.PODCAST)!!.state.value
        assertEquals(AudioSource.RssFeed("https://example.com/ep.mp3"), state.source)
        assertEquals("Restored Episode", state.title)
        assertEquals(42_000L, state.positionMs)
    }

    @Test
    fun restoringPodcastTrackAppliesThePodcastsDefaultSpeed() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.podcastDao().upsert(
                PodcastEntity(feedUrl = "https://feed.example.com", title = "Podcast",
                    artworkUrl = null, description = null, defaultSpeed = 1.5f)
            )
            db.episodeDao().upsertAll(listOf(
                EpisodeEntity(guid = "ep1", podcastFeedUrl = "https://feed.example.com",
                    title = "Episode 1", enclosureUrl = "https://example.com/ep1.mp3",
                    durationMs = 0L, showNotes = null, pubDateMs = 0L)
            ))
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/ep1.mp3", title = "Episode 1",
                    positionMs = 0L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.restorePlaybackStateForTesting()
        }
        assertEquals(1.5f, binder.getTrack(TrackId.PODCAST)!!.state.value.speed, 0.001f)
    }

    // Fix 3 (final review): of the three AudioSource branches restorePlaybackState() handles,
    // only RSS_FEED and the YOUTUBE cache-miss case previously had restore-side test coverage.
    // LOCAL_FILE has an external permission dependency and a Uri.parse() round-trip of its own.
    @Test
    fun restoringLocalFileTrackReloadsUriAndPosition() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        val uriString = "content://media/external/audio/media/1234"
        runBlocking {
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "MUSIC", sourceType = "LOCAL_FILE",
                    sourceRef = uriString, title = "Local Track",
                    positionMs = 7_000L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.MUSIC)
            binder.restorePlaybackStateForTesting()
        }
        val state = binder.getTrack(TrackId.MUSIC)!!.state.value
        assertEquals(AudioSource.LocalFile(Uri.parse(uriString)), state.source)
        assertEquals("Local Track", state.title)
        assertEquals(7_000L, state.positionMs)
    }

    // Fix 2 (final review): restorePlaybackState() must not trigger ExoPlayer's immediate
    // network buffering on a bare service (re)creation the user never asked for — e.g. an
    // unattended OS restart after a low-memory kill. TrackPlayer.setSource(prepare = false)
    // is used for the restore call site only; the player must stay STATE_IDLE until play()
    // is actually invoked.
    @Test
    fun restoringPlaybackStateDoesNotEagerlyPrepareThePlayer() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/ep.mp3", title = "Restored Episode",
                    positionMs = 42_000L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.restorePlaybackStateForTesting()
        }
        var playbackState = Player.STATE_READY
        instrumentation.runOnMainSync {
            playbackState = binder.getTrack(TrackId.PODCAST)!!.player.playbackState
        }
        assertEquals(
            "restoring a track must not start network buffering — the player should stay " +
                "STATE_IDLE until play() is actually called",
            Player.STATE_IDLE, playbackState
        )
    }

    @Test
    fun coldStartRestoreThenMediaButtonPressResumesBothTracks() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/ep.mp3", title = null,
                    positionMs = 10_000L, updatedAt = 0L,
                )
            )
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "MUSIC", sourceType = "RSS_FEED",
                    sourceRef = "https://example.com/music.mp3", title = null,
                    positionMs = 0L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
            binder.restorePlaybackStateForTesting()
            binder.simulateMediaButtonPress()
        }
        // Poll rather than a fixed Thread.sleep(400): the single-press handler only fires
        // after DOUBLE_PRESS_WINDOW_MS (300ms), so a fixed 400ms sleep leaves only a ~100ms
        // margin and can flake under load.
        assertTrue(
            "restored podcast track should resume on media-button press",
            waitUntilPlayWhenReady(TrackId.PODCAST, timeoutMs = 2_000)
        )
        assertTrue(
            "restored music track should resume on media-button press",
            waitUntilPlayWhenReady(TrackId.MUSIC, timeoutMs = 2_000)
        )
    }

    @Test
    fun restoringYoutubeTrackNotInCacheLeavesItSourcelessAndClearsStaleRow() {
        val db = OsseDatabase.get(ApplicationProvider.getApplicationContext())
        runBlocking {
            db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "MUSIC", sourceType = "YOUTUBE",
                    sourceRef = "missingVideoId", title = "Gone", positionMs = 0L, updatedAt = 0L,
                )
            )
        }
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.MUSIC)
            binder.restorePlaybackStateForTesting()
        }
        assertNull(binder.getTrack(TrackId.MUSIC)!!.state.value.source)
        val row = runBlocking { db.playbackStateDao().getByTrackId("MUSIC") }
        assertNull("stale playback_state row must be cleared when the cache entry is gone", row)
    }

    private fun waitUntilPlaying(id: TrackId, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var playing = false
            instrumentation.runOnMainSync {
                playing = binder.getTrack(id)?.state?.value?.isPlaying == true
            }
            if (playing) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun waitUntilPlayWhenReady(id: TrackId, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = binder.getTrack(id)?.player?.playWhenReady == true
            }
            if (ready) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun waitUntilPaused(id: TrackId, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var playWhenReady = true
            instrumentation.runOnMainSync {
                playWhenReady = binder.getTrack(id)?.player?.playWhenReady == true
            }
            if (!playWhenReady) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun waitUntilInForeground(expected: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var inForeground = false
            instrumentation.runOnMainSync { inForeground = binder.isInForegroundForTesting() }
            if (inForeground == expected) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun createSilentWavFile(durationSeconds: Int): File {
        val sampleRate = 8000
        val dataSize = sampleRate * durationSeconds // 8-bit mono: 1 byte/sample
        val file = File.createTempFile(
            "silence",
            ".wav",
            ApplicationProvider.getApplicationContext<Context>().cacheDir
        )
        FileOutputStream(file).use { out ->
            fun writeInt(v: Int) = out.write(
                byteArrayOf(
                    (v and 0xff).toByte(),
                    ((v shr 8) and 0xff).toByte(),
                    ((v shr 16) and 0xff).toByte(),
                    ((v shr 24) and 0xff).toByte(),
                )
            )
            fun writeShort(v: Int) = out.write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))

            out.write("RIFF".toByteArray())
            writeInt(36 + dataSize)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            writeInt(16) // fmt chunk size
            writeShort(1) // PCM
            writeShort(1) // mono
            writeInt(sampleRate)
            writeInt(sampleRate) // byte rate (sampleRate * channels * bitsPerSample/8)
            writeShort(1) // block align
            writeShort(8) // bits per sample
            out.write("data".toByteArray())
            writeInt(dataSize)

            val silence = ByteArray(4096) { 0x80.toByte() } // midpoint = silence for 8-bit unsigned PCM
            var remaining = dataSize
            while (remaining > 0) {
                val n = minOf(silence.size, remaining)
                out.write(silence, 0, n)
                remaining -= n
            }
        }
        return file
    }
}
