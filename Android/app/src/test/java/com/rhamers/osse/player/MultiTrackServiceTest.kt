package com.rhamers.osse.player

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiTrackServiceTest {

    private lateinit var podcast: TrackPlayer
    private lateinit var music: TrackPlayer

    @Before
    fun setUp() {
        podcast = TrackPlayer(ApplicationProvider.getApplicationContext(), TrackId.PODCAST)
        music = TrackPlayer(ApplicationProvider.getApplicationContext(), TrackId.MUSIC)
    }

    @After
    fun tearDown() {
        podcast.release()
        music.release()
    }

    @Test
    fun `no tracks requires no foreground`() {
        assertFalse(anyTrackRequiresForeground(emptyList()))
    }

    @Test
    fun `all tracks idle requires no foreground`() {
        assertFalse(anyTrackRequiresForeground(listOf(podcast, music)))
    }

    // The bug this guards against: the service's single MediaSession is dynamically bound to
    // whichever track is playing, preferring podcast — but Media3's own foreground-promotion
    // check only ever looks at that one session. If only the SECOND track (music) is playing
    // while the first (podcast) stays idle, this function must still say "yes."
    @Test
    fun `music playing while podcast idle still requires foreground`() {
        music.setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
        music.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(anyTrackRequiresForeground(listOf(podcast, music)))
    }

    @Test
    fun `podcast playing while music idle requires foreground`() {
        podcast.setSource(AudioSource.RssFeed("https://example.com/episode.mp3"))
        podcast.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(anyTrackRequiresForeground(listOf(podcast, music)))
    }

    @Test
    fun `pausing the only playing track drops the foreground requirement`() {
        music.setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
        music.play()
        shadowOf(Looper.getMainLooper()).idle()
        music.pause()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(anyTrackRequiresForeground(listOf(podcast, music)))
    }

    @Test
    fun `no tracks loaded has no active notification track`() {
        assertNull(activeNotificationTrack(null, null))
    }

    @Test
    fun `playing podcast is preferred over idle music for the notification`() {
        podcast.setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))
        podcast.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(podcast, activeNotificationTrack(podcast, music))
    }

    @Test
    fun `playing music is used for the notification when podcast is idle`() {
        music.setSource(AudioSource.RssFeed("https://example.com/music.mp3"))
        music.play()
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(music, activeNotificationTrack(podcast, music))
    }

    @Test
    fun `paused podcast with a loaded source still wins over idle music for the notification`() {
        podcast.setSource(AudioSource.RssFeed("https://example.com/ep.mp3"))

        assertSame(podcast, activeNotificationTrack(podcast, music))
    }
}
