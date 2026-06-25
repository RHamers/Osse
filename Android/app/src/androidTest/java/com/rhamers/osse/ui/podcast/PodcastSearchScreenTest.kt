package com.rhamers.osse.ui.podcast

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.data.repository.FakePodcastRepository
import com.rhamers.osse.model.Podcast
import com.rhamers.osse.ui.theme.OsseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodcastSearchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var repo: FakePodcastRepository
    private lateinit var viewModel: PodcastViewModel

    @Before
    fun setUp() {
        repo = FakePodcastRepository()
        viewModel = PodcastViewModel(repo)
    }

    @Test fun showsEmptyHintWhenNoSubscriptions() {
        composeTestRule.setContent { PodcastSearchScreen(viewModel, onPodcastClick = {}) }
        composeTestRule.onNodeWithText("No subscriptions yet.", substring = true).assertIsDisplayed()
    }

    @Test fun showsSubscribedPodcastTitle() {
        repo.podcasts.value = listOf(Podcast("https://feed.com/rss", "Darknet Diaries", null, null))

        composeTestRule.setContent { PodcastSearchScreen(viewModel, onPodcastClick = {}) }

        composeTestRule.waitUntil(2_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Darknet Diaries")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Darknet Diaries").assertIsDisplayed()
    }

    @Test fun tappingSubscribedPodcastInvokesCallback() {
        val podcast = Podcast("https://feed.com/rss", "Lex Fridman Podcast", null, null)
        repo.podcasts.value = listOf(podcast)

        var clicked: Podcast? = null
        composeTestRule.setContent {
            PodcastSearchScreen(viewModel, onPodcastClick = { clicked = it })
        }

        composeTestRule.waitUntil(2_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Lex Fridman Podcast")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Lex Fridman Podcast").performClick()

        assertEquals(podcast, clicked)
    }

    @Test fun showsSearchResultsAfterSearch() {
        repo.searchResult = listOf(
            PodcastIndexFeed(id = 1, title = "Security Now", feedUrl = "https://example.com/rss")
        )

        composeTestRule.setContent { PodcastSearchScreen(viewModel, onPodcastClick = {}) }

        // Trigger search programmatically via the ViewModel so we don't depend on IME
        composeTestRule.runOnUiThread { viewModel.search("security") }

        composeTestRule.waitUntil(2_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Security Now")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Security Now").assertIsDisplayed()
    }

    @Test fun subscribeButtonCallsSubscribeOnViewModel() {
        repo.searchResult = listOf(
            PodcastIndexFeed(id = 2, title = "The Daily", feedUrl = "https://daily.example.com/rss")
        )

        composeTestRule.setContent { PodcastSearchScreen(viewModel, onPodcastClick = {}) }
        composeTestRule.runOnUiThread { viewModel.search("daily") }

        composeTestRule.waitUntil(2_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Subscribe")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithContentDescription("Subscribe").performClick()

        composeTestRule.waitUntil(2_000) { repo.lastSubscribed != null }
        assertNotNull(repo.lastSubscribed)
        assertEquals("The Daily", repo.lastSubscribed!!.title)
    }

    @Test fun showsErrorMessageOnSearchFailure() {
        repo.searchError = RuntimeException("Network unreachable")

        composeTestRule.setContent { PodcastSearchScreen(viewModel, onPodcastClick = {}) }
        composeTestRule.runOnUiThread { viewModel.search("test") }

        composeTestRule.waitUntil(2_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Network unreachable")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Network unreachable").assertIsDisplayed()
    }

    @Test fun importOpmlButtonTriggersCallback() {
        var triggered = false
        composeTestRule.setContent {
            PodcastSearchScreen(viewModel, onPodcastClick = {}, onImportOpml = { triggered = true })
        }
        composeTestRule.onNodeWithContentDescription("Import OPML").performClick()
        assertTrue(triggered)
    }

    @Test fun exportOpmlButtonTriggersCallback() {
        var triggered = false
        composeTestRule.setContent {
            PodcastSearchScreen(viewModel, onPodcastClick = {}, onExportOpml = { triggered = true })
        }
        composeTestRule.onNodeWithContentDescription("Export OPML").performClick()
        assertTrue(triggered)
    }

    @Test fun importSuccessShowsSnackbar() {
        val snackbarHostState = SnackbarHostState()
        val opml = """<?xml version="1.0"?><opml version="1.0"><body>
            <outline xmlUrl="https://feed1.com/rss"/>
            <outline xmlUrl="https://feed2.com/rss"/>
            <outline xmlUrl="https://feed3.com/rss"/>
        </body></opml>"""

        composeTestRule.setContent {
            OsseTheme {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    PodcastSearchScreen(
                        viewModel = viewModel,
                        onPodcastClick = {},
                        snackbarHostState = snackbarHostState,
                        contentPadding = padding,
                    )
                }
            }
        }

        composeTestRule.runOnUiThread { viewModel.importOpml(opml.byteInputStream()) }

        composeTestRule.waitUntil(3_000) {
            composeTestRule.onAllNodes(hasText("3 podcasts imported")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("3 podcasts imported").assertIsDisplayed()
    }

    @Test fun importErrorShowsSnackbar() {
        val snackbarHostState = SnackbarHostState()
        repo.importError = RuntimeException("Import failed")
        val opml = """<?xml version="1.0"?><opml version="1.0"><body>
            <outline xmlUrl="https://feed1.com/rss"/>
        </body></opml>"""

        composeTestRule.setContent {
            OsseTheme {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    PodcastSearchScreen(
                        viewModel = viewModel,
                        onPodcastClick = {},
                        snackbarHostState = snackbarHostState,
                        contentPadding = padding,
                    )
                }
            }
        }

        composeTestRule.runOnUiThread { viewModel.importOpml(opml.byteInputStream()) }

        composeTestRule.waitUntil(3_000) {
            composeTestRule.onAllNodes(hasText("Import failed")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Import failed").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onNodeWithContentDescription(
    label: String
) = onNode(androidx.compose.ui.test.hasContentDescription(label))
