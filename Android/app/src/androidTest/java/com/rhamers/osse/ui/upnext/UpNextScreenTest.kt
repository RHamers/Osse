package com.rhamers.osse.ui.upnext

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhamers.osse.data.repository.FakePodcastRepository
import com.rhamers.osse.model.QueueItem
import com.rhamers.osse.ui.podcast.PodcastViewModel
import com.rhamers.osse.ui.theme.OsseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpNextScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var repo: FakePodcastRepository
    private lateinit var viewModel: PodcastViewModel

    @Before
    fun setUp() {
        repo = FakePodcastRepository()
        viewModel = PodcastViewModel(repo)
    }

    private fun setContent(
        onPlayEpisode: (String, String) -> Unit = { _, _ -> },
        onMarkComplete: (QueueItem) -> Unit = {}
    ) {
        composeRule.setContent {
            OsseTheme {
                UpNextScreen(
                    viewModel = viewModel,
                    onPlayEpisode = onPlayEpisode,
                    onMarkComplete = onMarkComplete
                )
            }
        }
    }

    private fun item(
        id: Long,
        episodeTitle: String,
        podcastTitle: String,
        guid: String = "guid$id",
        durationMs: Long = 3_600_000L
    ) = QueueItem(
        id = id,
        position = id.toInt() - 1,
        episodeGuid = guid,
        episodeTitle = episodeTitle,
        podcastTitle = podcastTitle,
        durationMs = durationMs,
        enclosureUrl = "https://example.com/$guid.mp3"
    )

    @Test fun showsScreenTitle() {
        setContent()
        composeRule.onNodeWithText("Up Next").assertIsDisplayed()
    }

    @Test fun showsEmptyStateWhenQueueIsEmpty() {
        setContent()
        composeRule.onNodeWithText("Nothing queued yet").assertIsDisplayed()
    }

    @Test fun emptyStateHiddenWhenQueueHasItems() {
        repo.queue.value = listOf(item(1, "Episode", "Podcast"))
        setContent()

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Episode")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Nothing queued yet").assertDoesNotExist()
    }

    @Test fun showsEpisodeTitleWhenQueued() {
        repo.queue.value = listOf(item(1, "Deep Dive Into Kotlin", "Talking Kotlin"))
        setContent()

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Deep Dive Into Kotlin")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Deep Dive Into Kotlin").assertIsDisplayed()
    }

    @Test fun showsPodcastNameWhenQueued() {
        repo.queue.value = listOf(item(1, "Episode", "Darknet Diaries"))
        setContent()

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Darknet Diaries", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Darknet Diaries", substring = true).assertIsDisplayed()
    }

    @Test fun showsMultipleQueuedItems() {
        repo.queue.value = listOf(
            item(1, "Episode One", "Podcast A"),
            item(2, "Episode Two", "Podcast B")
        )
        setContent()

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Episode One")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Episode One").assertIsDisplayed()
        composeRule.onNodeWithText("Episode Two").assertIsDisplayed()
    }

    @Test fun playButtonInvokesOnPlayEpisodeWithCorrectUrlAndTitle() {
        val queued = item(1, "Play Me", "Podcast")
        repo.queue.value = listOf(queued)

        var playedUrl: String? = null
        var playedTitle: String? = null
        setContent(onPlayEpisode = { url, title -> playedUrl = url; playedTitle = title })

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Play")).performClick()

        assertEquals(queued.enclosureUrl, playedUrl)
        assertEquals(queued.episodeTitle, playedTitle)
    }

    @Test fun removeButtonCallsRemoveFromQueueWithCorrectId() {
        repo.queue.value = listOf(item(id = 7L, episodeTitle = "Episode", podcastTitle = "Podcast"))
        setContent()

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasContentDescription("Remove")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Remove")).performClick()

        composeRule.waitUntil(2_000) { repo.removedQueueIds.contains(7L) }
        assertTrue(repo.removedQueueIds.contains(7L))
    }

    @Test fun markAsDoneButtonIsDisplayedForEachItem() {
        repo.queue.value = listOf(item(1, "Episode", "Podcast"))
        setContent()

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasContentDescription("Mark as done")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Mark as done")).assertIsDisplayed()
    }

    @Test fun markAsDoneButtonInvokesCallbackWithCorrectItem() {
        val queued = item(id = 5L, episodeTitle = "Finish Me", podcastTitle = "Podcast")
        repo.queue.value = listOf(queued)

        var completed: QueueItem? = null
        setContent(onMarkComplete = { completed = it })

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasContentDescription("Mark as done")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Mark as done")).performClick()

        assertEquals(queued, completed)
    }

    @Test fun dragHandleIsDisplayedForEachItem() {
        repo.queue.value = listOf(item(1, "Episode", "Podcast"))
        setContent()

        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasContentDescription("Reorder")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Reorder")).assertIsDisplayed()
    }

    @Test fun nowPlayingIndicatorShownForActiveEpisode() {
        val playing = item(1, "Playing Now", "Podcast")
        repo.queue.value = listOf(playing)
        composeRule.setContent {
            OsseTheme {
                UpNextScreen(
                    viewModel = viewModel,
                    onPlayEpisode = { _, _ -> },
                    onMarkComplete = {},
                    currentPlayingUrl = playing.enclosureUrl
                )
            }
        }
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasContentDescription("Now playing")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Now playing")).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Play")).assertDoesNotExist()
    }

    @Test fun playButtonShownForNonActiveEpisode() {
        val notPlaying = item(1, "Waiting", "Podcast")
        repo.queue.value = listOf(notPlaying)
        composeRule.setContent {
            OsseTheme {
                UpNextScreen(
                    viewModel = viewModel,
                    onPlayEpisode = { _, _ -> },
                    onMarkComplete = {},
                    currentPlayingUrl = "https://example.com/other.mp3"
                )
            }
        }
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Play")).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("Now playing")).assertDoesNotExist()
    }
}
