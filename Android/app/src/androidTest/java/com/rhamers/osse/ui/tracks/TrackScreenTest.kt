package com.rhamers.osse.ui.tracks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.player.FakeYoutubeCacheManager
import com.rhamers.osse.player.MultiTrackService
import com.rhamers.osse.ui.theme.OsseTheme
import com.rhamers.osse.youtube.VideoSearchResult
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TrackScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

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
    fun resetTracks() {
        instrumentation.runOnMainSync {
            binder.clearTracksForTesting()
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
        }
    }

    @Test
    fun showsLoadingIndicatorWhenBinderIsNull() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = null) }
        }
        composeRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun loadingIndicatorNotShownWhenBound() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithContentDescription("Loading").assertDoesNotExist()
    }

    @Test
    fun showsTrack1Label() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("Track 1").assertIsDisplayed()
    }

    @Test
    fun showsTrack2Label() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("Track 2").assertIsDisplayed()
    }

    @Test
    fun showsNoSourceLoadedForBothTracks() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onAllNodes(hasContentDescription("Select source")).assertCountEquals(2)
    }

    @Test
    fun bothTracksShowPlayButton() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onAllNodes(hasContentDescription("Play")).assertCountEquals(2)
    }

    @Test
    fun bothTracksShowSourceDropdown() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onAllNodes(hasContentDescription("Select source")).assertCountEquals(2)
    }

    @Test
    fun noAddTrackButtonShown() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithContentDescription("Add track").assertDoesNotExist()
    }

    @Test
    fun noEmptyStateShown() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("No tracks").assertDoesNotExist()
    }

    private fun openYoutubeDialog() {
        composeRule.onAllNodes(hasContentDescription("Select source"))[0].performClick()
        composeRule.onNodeWithText("YouTube").performClick()
    }

    private fun typeIntoYoutubeField(text: String) {
        composeRule.onNodeWithText("Search or paste URL").performTextInput(text)
    }

    @Test
    fun clickingYoutubeOptionShowsDialog() {
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder) } }
        openYoutubeDialog()
        composeRule.onNodeWithText("YouTube").assertIsDisplayed()
        composeRule.onNodeWithText("Search or paste URL").assertIsDisplayed()
    }

    @Test
    fun youtubeDialogShowsCancelButton() {
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder) } }
        openYoutubeDialog()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun youtubeSearchButtonDisabledWhenInputEmpty() {
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder) } }
        openYoutubeDialog()
        composeRule.onNodeWithContentDescription("Search").assertIsNotEnabled()
    }

    @Test
    fun youtubeDialogCancelDismissesDialog() {
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder) } }
        openYoutubeDialog()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Search or paste URL").assertDoesNotExist()
    }

    @Test
    fun youtubeUrlInputShowsPlayAndDownloadButtons() {
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder) } }
        openYoutubeDialog()
        typeIntoYoutubeField("https://youtube.com/watch?v=abc")
        composeRule.onNodeWithText("Play").assertIsDisplayed()
        composeRule.onNodeWithText("Download").assertIsDisplayed()
    }

    @Test
    fun youtubeExtractionSuccessClosesDialogAndSetsSource() {
        val fakeManager = FakeYoutubeCacheManager(resolveResult = Result.success(AudioSource.YoutubeStream("https://fake-stream.example.com/audio")))
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("https://youtube.com/watch?v=abc")
        composeRule.onNodeWithText("Play").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Search or paste URL")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("YouTube stream").assertIsDisplayed()
    }

    @Test
    fun youtubePlayStartsPlaybackWithoutASecondPress() {
        val fakeManager = FakeYoutubeCacheManager(resolveResult = Result.success(AudioSource.YoutubeStream("https://fake-stream.example.com/audio")))
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("https://youtube.com/watch?v=abc")
        composeRule.onNodeWithText("Play").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Search or paste URL")).fetchSemanticsNodes().isEmpty()
        }
        var playWhenReady = false
        instrumentation.runOnMainSync {
            playWhenReady = binder.getTrack(TrackId.PODCAST)!!.player.playWhenReady
        }
        assertTrue(
            "the dialog's Play button must start playback, not just load the source",
            playWhenReady,
        )
    }

    @Test
    fun youtubeExtractionFailureShowsError() {
        val fakeManager = FakeYoutubeCacheManager(resolveResult = Result.failure(RuntimeException("cipher error")))
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("https://youtube.com/watch?v=abc")
        composeRule.onNodeWithText("Play").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("cipher error")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("cipher error").assertIsDisplayed()
        composeRule.onNodeWithText("YouTube").assertIsDisplayed()
    }

    @Test
    fun youtubeDownloadSuccessClosesDialogAndSetsSource() {
        val fakeManager = FakeYoutubeCacheManager(downloadResult = Result.success(Unit))
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("https://youtube.com/watch?v=abc")
        composeRule.onNodeWithText("Download").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Search or paste URL")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("abc.webm").assertIsDisplayed()
    }

    @Test
    fun youtubeDownloadFailureShowsError() {
        val fakeManager = FakeYoutubeCacheManager(downloadResult = Result.failure(RuntimeException("disk full")))
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("https://youtube.com/watch?v=abc")
        composeRule.onNodeWithText("Download").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("disk full")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("disk full").assertIsDisplayed()
        composeRule.onNodeWithText("YouTube").assertIsDisplayed()
    }

    @Test
    fun youtubeSearchShowsResults() {
        val fakeManager = FakeYoutubeCacheManager(
            searchResult = Result.success(listOf(
                VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225),
                VideoSearchResult("vid2", "Piano Music", "Relaxing", 3730),
            ))
        )
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("lofi")
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Lofi Beats")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Lofi Beats").assertIsDisplayed()
        composeRule.onNodeWithText("Piano Music").assertIsDisplayed()
    }

    @Test
    fun youtubeSearchResultPlayClosesDialogAndSetsStream() {
        val fakeManager = FakeYoutubeCacheManager(
            resolveResult = Result.success(AudioSource.YoutubeStream("https://fake-stream.example.com/audio")),
            searchResult = Result.success(listOf(
                VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225),
            ))
        )
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("lofi")
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Lofi Beats")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Play Lofi Beats").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Search or paste URL")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("YouTube stream").assertIsDisplayed()
    }

    @Test
    fun youtubeSearchResultDownloadClosesDialogAndSetsSource() {
        val fakeManager = FakeYoutubeCacheManager(
            downloadResult = Result.success(Unit),
            searchResult = Result.success(listOf(
                VideoSearchResult("vid1", "Lofi Beats", "Lofi Girl", 225),
            ))
        )
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("lofi")
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Lofi Beats")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Download Lofi Beats").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Search or paste URL")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("vid1.webm").assertIsDisplayed()
    }

    @Test
    fun youtubeSearchFailureShowsError() {
        val fakeManager = FakeYoutubeCacheManager(
            searchResult = Result.failure(RuntimeException("network error"))
        )
        composeRule.setContent { OsseTheme { TrackScreen(binder = binder, cacheManager = fakeManager) } }
        openYoutubeDialog()
        typeIntoYoutubeField("lofi")
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("network error")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("network error").assertIsDisplayed()
    }
}
