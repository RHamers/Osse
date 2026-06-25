package com.rhamers.osse.ui.tracks

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setCard(
        state: TrackState,
        isExtracting: Boolean = false,
        onPlay: () -> Unit = {},
        onPause: () -> Unit = {},
        onRewind: () -> Unit = {},
        onForward: () -> Unit = {},
        onVolumeChange: (Float) -> Unit = {},
        onSpeedChange: (Float) -> Unit = {},
        onSeek: (Long) -> Unit = {},
        onSelectPodcast: () -> Unit = {},
        onSelectLocalFile: () -> Unit = {},
        onSelectYoutube: () -> Unit = {},
        onMarkComplete: (() -> Unit)? = null,
        onTitleClick: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            TrackCard(
                label = "Track 1",
                state = state,
                isExtracting = isExtracting,
                onPlay = onPlay,
                onPause = onPause,
                onRewind = onRewind,
                onForward = onForward,
                onVolumeChange = onVolumeChange,
                onSpeedChange = onSpeedChange,
                onSeek = onSeek,
                onSelectPodcast = onSelectPodcast,
                onSelectLocalFile = onSelectLocalFile,
                onSelectYoutube = onSelectYoutube,
                onMarkComplete = onMarkComplete,
                onTitleClick = onTitleClick,
            )
        }
    }

    @Test
    fun showsTrackLabel() {
        setCard(TrackState())
        composeRule.onNodeWithText("Track 1").assertIsDisplayed()
    }

    @Test
    fun showsNoSourceLoadedWhenSourceIsNull() {
        setCard(TrackState(source = null))
        composeRule.onNodeWithText("No source loaded").assertIsDisplayed()
    }

    @Test
    fun showsSourceUrlWhenRssFeedSet() {
        val url = "https://example.com/episode.mp3"
        setCard(TrackState(source = AudioSource.RssFeed(url)))
        composeRule.onNodeWithText(url).assertIsDisplayed()
    }

    @Test
    fun showsTitleInsteadOfUrlWhenTitleIsSet() {
        val url = "https://example.com/episode.mp3"
        setCard(TrackState(source = AudioSource.RssFeed(url), title = "My Episode Title"))
        composeRule.onNodeWithText("My Episode Title").assertIsDisplayed()
        composeRule.onNodeWithText(url).assertDoesNotExist()
    }

    @Test
    fun showsPlayButtonWhenNotPlaying() {
        setCard(TrackState(isPlaying = false))
        composeRule.onNodeWithContentDescription("Play").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun showsPauseButtonWhenPlaying() {
        setCard(TrackState(isPlaying = true))
        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun showsBufferingIndicatorWhenLoading() {
        setCard(TrackState(isLoading = true))
        composeRule.onNodeWithContentDescription("Buffering").assertIsDisplayed()
    }

    @Test
    fun hidesBufferingIndicatorWhenNotLoading() {
        setCard(TrackState(isLoading = false))
        composeRule.onNodeWithContentDescription("Buffering").assertDoesNotExist()
    }

    @Test
    fun showsVolumePercentage() {
        setCard(TrackState(volume = 0.75f))
        composeRule.onNodeWithText("75%").assertIsDisplayed()
    }

    @Test
    fun shows100PercentAtFullVolume() {
        setCard(TrackState(volume = 1.0f))
        composeRule.onNodeWithText("100%").assertIsDisplayed()
    }

    @Test
    fun showsDefaultSpeedLabel() {
        setCard(TrackState(speed = 1.0f))
        composeRule.onNodeWithText("1.0x").assertIsDisplayed()
    }

    @Test
    fun showsCurrentSpeedLabel() {
        setCard(TrackState(speed = 1.5f))
        composeRule.onNodeWithText("1.5x").assertIsDisplayed()
    }

    @Test
    fun speedSliderCallsOnSpeedChange() {
        var receivedSpeed = -1f
        setCard(TrackState(speed = 1.0f), onSpeedChange = { receivedSpeed = it })
        composeRule.onNodeWithTag("speed_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2.0f) }
        assertEquals(2.0f, receivedSpeed, 0.001f)
    }

    @Test
    fun rewindButtonIsDisplayed() {
        setCard(TrackState())
        composeRule.onNodeWithContentDescription("Rewind 10 seconds").assertIsDisplayed()
    }

    @Test
    fun forwardButtonIsDisplayed() {
        setCard(TrackState())
        composeRule.onNodeWithContentDescription("Forward 30 seconds").assertIsDisplayed()
    }

    @Test
    fun clickingRewindCallsOnRewind() {
        var called = false
        setCard(TrackState(), onRewind = { called = true })
        composeRule.onNodeWithContentDescription("Rewind 10 seconds").performClick()
        assertTrue(called)
    }

    @Test
    fun clickingForwardCallsOnForward() {
        var called = false
        setCard(TrackState(), onForward = { called = true })
        composeRule.onNodeWithContentDescription("Forward 30 seconds").performClick()
        assertTrue(called)
    }

    @Test
    fun clickingPlayCallsOnPlay() {
        var called = false
        setCard(TrackState(isPlaying = false), onPlay = { called = true })
        composeRule.onNodeWithContentDescription("Play").performClick()
        assertTrue(called)
    }

    @Test
    fun clickingPauseCallsOnPause() {
        var called = false
        setCard(TrackState(isPlaying = true), onPause = { called = true })
        composeRule.onNodeWithContentDescription("Pause").performClick()
        assertTrue(called)
    }

    @Test
    fun positionControlsHiddenWhenDurationIsZero() {
        setCard(TrackState(durationMs = 0L, positionMs = 0L))
        composeRule.onAllNodesWithText("0:00").assertCountEquals(0)
    }

    @Test
    fun positionControlsShownWhenDurationKnown() {
        setCard(TrackState(durationMs = 90_000L, positionMs = 30_000L))
        composeRule.onNodeWithText("0:30").assertIsDisplayed()
        composeRule.onNodeWithText("1:30").assertIsDisplayed()
    }

    @Test
    fun volumeAndSpeedSlidersVisibleWhilePositionKnown() {
        // When duration is known the position slider appears, making the card content taller.
        // Without verticalScroll on the inner Column the card (constrained by weight(1f)) clips
        // the overflow and the volume/speed rows become invisible. This is a regression test.
        setCard(TrackState(
            source = AudioSource.RssFeed("https://example.com/ep.mp3"),
            isPlaying = true,
            durationMs = 3_600_000L,
            positionMs = 60_000L,
        ))
        composeRule.onNodeWithText("100%").assertIsDisplayed()
        composeRule.onNodeWithTag("speed_slider").assertIsDisplayed()
    }

    @Test
    fun sourceDropdownButtonIsDisplayed() {
        setCard(TrackState())
        composeRule.onNodeWithContentDescription("Select source").assertIsDisplayed()
    }

    @Test
    fun sourceDropdownShowsOptionsOnClick() {
        setCard(TrackState())
        composeRule.onNodeWithContentDescription("Select source").performClick()
        composeRule.onNodeWithText("Podcast").assertIsDisplayed()
        composeRule.onNodeWithText("Local File").assertIsDisplayed()
    }

    @Test
    fun clickingPodcastOptionCallsOnSelectPodcast() {
        var called = false
        setCard(TrackState(), onSelectPodcast = { called = true })
        composeRule.onNodeWithContentDescription("Select source").performClick()
        composeRule.onNodeWithText("Podcast").performClick()
        assertTrue(called)
    }

    @Test
    fun clickingLocalFileOptionCallsOnSelectLocalFile() {
        var called = false
        setCard(TrackState(), onSelectLocalFile = { called = true })
        composeRule.onNodeWithContentDescription("Select source").performClick()
        composeRule.onNodeWithText("Local File").performClick()
        assertTrue(called)
    }

    @Test
    fun markAsDoneButtonHiddenWhenCallbackIsNull() {
        setCard(TrackState(source = AudioSource.RssFeed("https://example.com/ep.mp3")))
        composeRule.onNodeWithContentDescription("Mark as done").assertDoesNotExist()
    }

    @Test
    fun markAsDoneButtonHiddenWhenSourceIsNull() {
        setCard(TrackState(source = null), onMarkComplete = {})
        composeRule.onNodeWithContentDescription("Mark as done").assertDoesNotExist()
    }

    @Test
    fun markAsDoneButtonHiddenWhenSourceIsLocalFile() {
        val uri = android.net.Uri.parse("content://media/external/audio/1")
        setCard(TrackState(source = AudioSource.LocalFile(uri)), onMarkComplete = {})
        composeRule.onNodeWithContentDescription("Mark as done").assertDoesNotExist()
    }

    @Test
    fun markAsDoneButtonShownWhenCallbackProvidedAndSourceIsRssFeed() {
        setCard(
            TrackState(source = AudioSource.RssFeed("https://example.com/ep.mp3")),
            onMarkComplete = {}
        )
        composeRule.onNodeWithContentDescription("Mark as done").assertIsDisplayed()
    }

    @Test
    fun clickingMarkAsDoneCallsCallback() {
        var called = false
        setCard(
            TrackState(source = AudioSource.RssFeed("https://example.com/ep.mp3")),
            onMarkComplete = { called = true }
        )
        composeRule.onNodeWithContentDescription("Mark as done").performClick()
        assertTrue(called)
    }

    @Test
    fun sourceDropdownShowsYoutubeOption() {
        setCard(TrackState())
        composeRule.onNodeWithContentDescription("Select source").performClick()
        composeRule.onNodeWithText("YouTube").assertIsDisplayed()
    }

    @Test
    fun clickingYoutubeOptionCallsOnSelectYoutube() {
        var called = false
        setCard(TrackState(), onSelectYoutube = { called = true })
        composeRule.onNodeWithContentDescription("Select source").performClick()
        composeRule.onNodeWithText("YouTube").performClick()
        assertTrue(called)
    }

    @Test
    fun showsExtractingSpinnerWhenExtracting() {
        setCard(TrackState(), isExtracting = true)
        composeRule.onNodeWithContentDescription("Extracting").assertIsDisplayed()
    }

    @Test
    fun hidesExtractingSpinnerWhenNotExtracting() {
        setCard(TrackState(), isExtracting = false)
        composeRule.onNodeWithContentDescription("Extracting").assertDoesNotExist()
    }

    @Test
    fun showsYoutubeStreamDisplayString() {
        setCard(TrackState(source = AudioSource.YoutubeStream("https://stream.example.com/audio")))
        composeRule.onNodeWithText("YouTube stream").assertIsDisplayed()
    }

    // ── title click ──────────────────────────────────────────────────────────

    @Test
    fun clickingTitleWithRssFeedAndCallbackInvokesCallback() {
        var called = false
        setCard(
            state = TrackState(source = AudioSource.RssFeed("https://example.com/ep.mp3"), title = "My Episode"),
            onTitleClick = { called = true }
        )
        composeRule.onNodeWithText("My Episode").performClick()
        assertTrue(called)
    }

    @Test
    fun clickingTitleWithNonRssFeedSourceDoesNotInvokeCallback() {
        var called = false
        setCard(
            state = TrackState(source = AudioSource.YoutubeStream("https://stream.example.com"), title = "Some Video"),
            onTitleClick = { called = true }
        )
        composeRule.onNodeWithText("Some Video").performClick()
        assertFalse(called)
    }

    @Test
    fun titleWithNoCallbackIsNotClickable() {
        var called = false
        setCard(
            state = TrackState(source = AudioSource.RssFeed("https://example.com/ep.mp3"), title = "My Episode"),
            onTitleClick = null
        )
        composeRule.onNodeWithText("My Episode").performClick()
        assertFalse(called)
    }
}
