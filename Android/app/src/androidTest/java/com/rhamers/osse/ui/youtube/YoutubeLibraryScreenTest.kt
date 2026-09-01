package com.rhamers.osse.ui.youtube

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.ui.theme.OsseTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YoutubeLibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entity(
        videoId: String = "vid1",
        title: String = "Lofi Beats",
        channel: String = "Lofi Girl",
        durationSecs: Long = 225L,
    ) = YoutubeCacheEntity(
        videoId = videoId,
        title = title,
        channel = channel,
        durationSecs = durationSecs,
        thumbnailPath = "/fake/$videoId.jpg",
        localPath = "/fake/$videoId.webm",
        cachedAt = 1_000_000L,
    )

    @Test
    fun showsEmptyStateWhenNoVideos() {
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(emptyList()),
                    onPlay = {},
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithText("No cached videos yet").assertIsDisplayed()
    }

    @Test
    fun showsTitleAndChannelForCachedVideo() {
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(listOf(entity())),
                    onPlay = {},
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithText("Lofi Beats").assertIsDisplayed()
        composeRule.onNodeWithText("Lofi Girl", substring = true).assertIsDisplayed()
    }

    @Test
    fun showsMultipleVideos() {
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(listOf(
                        entity("vid1", "Lofi Beats", "Lofi Girl"),
                        entity("vid2", "Piano Music", "Relaxing"),
                    )),
                    onPlay = {},
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithText("Lofi Beats").assertIsDisplayed()
        composeRule.onNodeWithText("Piano Music").assertIsDisplayed()
    }

    @Test
    fun clickingDeleteCallsOnDeleteWithVideoId() {
        var deletedId: String? = null
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(listOf(entity())),
                    onPlay = {},
                    onDelete = { id -> deletedId = id },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Delete Lofi Beats").performClick()
        assertEquals("vid1", deletedId)
    }

    @Test
    fun clickingPlayCallsOnPlayWithEntity() {
        var playedId: String? = null
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(listOf(entity())),
                    onPlay = { e -> playedId = e.videoId },
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Play Lofi Beats").performClick()
        assertEquals("vid1", playedId)
    }

    @Test
    fun deleteButtonNotPresentInEmptyState() {
        composeRule.setContent {
            OsseTheme {
                YoutubeLibraryScreen(
                    cachedVideos = flowOf(emptyList()),
                    onPlay = {},
                    onDelete = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Delete Lofi Beats").assertDoesNotExist()
    }
}
