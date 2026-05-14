package com.rhamers.osse.ui.tracks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.player.MultiTrackService
import com.rhamers.osse.ui.theme.OsseTheme
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
    fun clearTracks() {
        instrumentation.runOnMainSync { binder.clearTracksForTesting() }
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
    fun showsEmptyStateWhenNoTracks() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("No tracks").assertIsDisplayed()
    }

    @Test
    fun showsAddTrackButtonWhenEmpty() {
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("Add Track").assertIsDisplayed()
    }

    @Test
    fun showsTrackCardAfterAddingTrack() {
        instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("Podcast").assertIsDisplayed()
    }

    @Test
    fun showsNoSourceLoadedAfterAddingTrack() {
        instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("No source loaded").assertIsDisplayed()
    }

    @Test
    fun showsBothTrackLabelsAfterAddingBoth() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
        }
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("Podcast").assertIsDisplayed()
        composeRule.onNodeWithText("Music").assertIsDisplayed()
    }

    @Test
    fun fabShownWhenOneTrackMissing() {
        instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithContentDescription("Add track").assertIsDisplayed()
    }

    @Test
    fun fabNotShownWhenAllTracksAdded() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
        }
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithContentDescription("Add track").assertDoesNotExist()
    }

    @Test
    fun emptyStateHiddenAfterAddingTrack() {
        instrumentation.runOnMainSync { binder.addTrack(TrackId.PODCAST) }
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onNodeWithText("No tracks").assertDoesNotExist()
    }

    @Test
    fun bothTracksShowPlayButtonAfterAdding() {
        instrumentation.runOnMainSync {
            binder.addTrack(TrackId.PODCAST)
            binder.addTrack(TrackId.MUSIC)
        }
        composeRule.setContent {
            OsseTheme { TrackScreen(binder = binder) }
        }
        composeRule.onAllNodes(hasContentDescription("Play")).assertCountEquals(2)
    }
}
