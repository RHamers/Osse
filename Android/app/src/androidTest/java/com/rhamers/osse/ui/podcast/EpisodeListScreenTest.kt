package com.rhamers.osse.ui.podcast

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhamers.osse.data.repository.FakePodcastRepository
import com.rhamers.osse.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var repo: FakePodcastRepository
    private lateinit var viewModel: PodcastViewModel

    private val feedUrl = "https://feed.example.com/rss"

    @Before
    fun setUp() {
        repo = FakePodcastRepository()
        viewModel = PodcastViewModel(repo)
    }

    private fun episode(
        guid: String = "ep1",
        title: String = "Episode Title",
        durationMs: Long = 3_600_000L,
        pubDateMs: Long = 1_747_040_400_000L // 2025-05-12
    ) = Episode(
        guid = guid,
        podcastFeedUrl = feedUrl,
        title = title,
        enclosureUrl = "https://example.com/$guid.mp3",
        durationMs = durationMs,
        showNotes = null,
        pubDateMs = pubDateMs
    )

    private fun setContent(
        onPlay: (Episode) -> Unit = {},
        onBack: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            EpisodeListScreen(
                feedUrl = feedUrl,
                podcastTitle = "Test Podcast",
                viewModel = viewModel,
                onPlayEpisode = onPlay,
                onBack = onBack
            )
        }
    }

    @Test fun showsPodcastTitleInTopBar() {
        setContent()
        composeTestRule.onNodeWithText("Test Podcast").assertIsDisplayed()
    }

    @Test fun showsEmptyStateWhenNoEpisodes() {
        setContent()
        composeTestRule.onNodeWithText("No episodes yet.").assertIsDisplayed()
    }

    @Test fun showsEpisodeTitleAndDuration() {
        repo.setEpisodes(feedUrl, listOf(episode(title = "Deep Dive into Kotlin")))
        setContent()

        composeTestRule.waitUntil(2_000) {
            composeTestRule.onAllNodes(hasText("Deep Dive into Kotlin")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Deep Dive into Kotlin").assertIsDisplayed()
        composeTestRule.onNodeWithText("1:00:00").assertIsDisplayed() // 3600s
    }

    @Test fun showsMultipleEpisodes() {
        repo.setEpisodes(feedUrl, listOf(
            episode(guid = "ep1", title = "Episode One"),
            episode(guid = "ep2", title = "Episode Two")
        ))
        setContent()

        composeTestRule.waitUntil(2_000) {
            composeTestRule.onAllNodes(hasText("Episode One")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Episode One").assertIsDisplayed()
        composeTestRule.onNodeWithText("Episode Two").assertIsDisplayed()
    }

    @Test fun backButtonInvokesOnBack() {
        var backCalled = false
        setContent(onBack = { backCalled = true })

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.runOnIdle { assert(backCalled) }
    }

    @Test fun playButtonInvokesOnPlayEpisode() {
        val ep = episode(guid = "ep1", title = "Play Me")
        repo.setEpisodes(feedUrl, listOf(ep))

        var played: Episode? = null
        setContent(onPlay = { played = it })

        composeTestRule.waitUntil(2_000) {
            composeTestRule.onAllNodes(hasText("Play Me")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasContentDescription("Play")).performClick()

        composeTestRule.runOnIdle {
            assertNotNull(played)
            assertEquals("ep1", played!!.guid)
        }
    }

    @Test fun refreshButtonCallsViewModelRefresh() {
        setContent()
        composeTestRule.onNodeWithContentDescription("Refresh").performClick()
        composeTestRule.runOnIdle {
            assertEquals(feedUrl, repo.lastRefreshed)
        }
    }
}
