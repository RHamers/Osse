package com.rhamers.osse.ui.podcast

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhamers.osse.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun episode(
        guid: String = "ep1",
        title: String = "Test Episode",
        durationMs: Long = 3_600_000L,
        pubDateMs: Long = 1_747_040_400_000L, // 2025-05-12
        showNotes: String? = "<p>Show notes here.</p>"
    ) = Episode(
        guid = guid,
        podcastFeedUrl = "https://feed.example.com/rss",
        title = title,
        enclosureUrl = "https://example.com/$guid.mp3",
        durationMs = durationMs,
        showNotes = showNotes,
        pubDateMs = pubDateMs
    )

    private fun setContent(
        episode: Episode = episode(),
        onPlay: (Episode) -> Unit = {},
        onAddToQueue: (Episode) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            EpisodeDetailScreen(
                episode = episode,
                onPlay = onPlay,
                onAddToQueue = onAddToQueue,
                onBack = onBack
            )
        }
    }

    @Test fun showsEpisodeTitle() {
        setContent(episode = episode(title = "Deep Dive into Kotlin"))
        composeTestRule.onNodeWithText("Deep Dive into Kotlin").assertIsDisplayed()
    }

    @Test fun showsDuration() {
        setContent(episode = episode(durationMs = 3_600_000L))
        composeTestRule.onNodeWithText("1:00:00").assertIsDisplayed()
    }

    @Test fun showsPublishDate() {
        setContent(episode = episode(pubDateMs = 1_747_040_400_000L))
        composeTestRule.onNodeWithText("May 12, 2025").assertIsDisplayed()
    }

    @Test fun backButtonInvokesOnBack() {
        var called = false
        setContent(onBack = { called = true })
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(called)
    }

    @Test fun playButtonInvokesOnPlay() {
        val ep = episode(guid = "ep1")
        var played: Episode? = null
        setContent(episode = ep, onPlay = { played = it })
        composeTestRule.onNodeWithContentDescription("Play").performClick()
        assertNotNull(played)
        assertEquals("ep1", played!!.guid)
    }

    @Test fun addToQueueButtonInvokesOnAddToQueue() {
        val ep = episode(guid = "ep1")
        var queued: Episode? = null
        setContent(episode = ep, onAddToQueue = { queued = it })
        composeTestRule.onNodeWithContentDescription("Add to queue").performClick()
        assertNotNull(queued)
        assertEquals("ep1", queued!!.guid)
    }

    @Test fun showsNoShowNotesFallbackWhenNull() {
        setContent(episode = episode(showNotes = null))
        composeTestRule.onNodeWithText("No show notes available.").assertIsDisplayed()
    }

    @Test fun doesNotShowNoShowNotesFallbackWhenNotesPresent() {
        setContent(episode = episode(showNotes = "<p>Content</p>"))
        composeTestRule.onNodeWithText("No show notes available.").assertDoesNotExist()
    }
}
