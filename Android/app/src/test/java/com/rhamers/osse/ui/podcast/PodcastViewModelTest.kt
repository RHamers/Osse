package com.rhamers.osse.ui.podcast

import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.data.repository.FakePodcastRepository
import com.rhamers.osse.model.Podcast
import com.rhamers.osse.util.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PodcastViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repo: FakePodcastRepository
    private lateinit var viewModel: PodcastViewModel

    @Before
    fun setUp() {
        repo = FakePodcastRepository()
        viewModel = PodcastViewModel(repo)
    }

    // ── search state ─────────────────────────────────────────────────────────

    @Test fun `initial search state is Idle`() {
        assertTrue(viewModel.searchState.value is SearchState.Idle)
    }

    @Test fun `blank query keeps state Idle`() = runTest {
        viewModel.search("   ")
        assertTrue(viewModel.searchState.value is SearchState.Idle)
    }

    @Test fun `successful search transitions to Results`() = runTest {
        val feeds = listOf(PodcastIndexFeed(id = 1, title = "Darknet Diaries", feedUrl = "https://example.com/rss"))
        repo.searchResult = feeds

        viewModel.search("darknet")

        val state = viewModel.searchState.value
        assertTrue(state is SearchState.Results)
        assertEquals(feeds, (state as SearchState.Results).feeds)
    }

    @Test fun `failed search transitions to Error with message`() = runTest {
        repo.searchError = RuntimeException("timeout")

        viewModel.search("test")

        val state = viewModel.searchState.value
        assertTrue(state is SearchState.Error)
        assertEquals("timeout", (state as SearchState.Error).message)
    }

    @Test fun `clearSearch resets state to Idle`() = runTest {
        repo.searchResult = listOf(PodcastIndexFeed(id = 1, title = "X", feedUrl = "url"))
        viewModel.search("x")
        assertTrue(viewModel.searchState.value is SearchState.Results)

        viewModel.clearSearch()
        assertTrue(viewModel.searchState.value is SearchState.Idle)
    }

    // ── delegate calls ───────────────────────────────────────────────────────

    @Test fun `subscribe delegates to repo`() = runTest {
        val feed = PodcastIndexFeed(id = 42, title = "My Pod", feedUrl = "https://example.com/rss")
        viewModel.subscribe(feed)
        assertEquals(feed, repo.lastSubscribed)
    }

    @Test fun `subscribeByUrl delegates to repo`() = runTest {
        viewModel.subscribeByUrl("https://example.com/rss")
        assertEquals("https://example.com/rss", repo.lastSubscribedUrl)
    }

    @Test fun `unsubscribe delegates to repo`() = runTest {
        viewModel.unsubscribe("https://example.com/rss")
        assertEquals("https://example.com/rss", repo.lastUnsubscribed)
    }

    // ── startup auto-refresh ─────────────────────────────────────────────────

    @Test fun `constructing the view model refreshes all podcasts once`() = runTest {
        assertEquals(1, repo.refreshAllPodcastsCallCount)
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test fun `refresh delegates to repo and clears isRefreshing`() = runTest {
        assertFalse(viewModel.isRefreshing.value)
        viewModel.refresh("https://example.com/rss")
        assertFalse(viewModel.isRefreshing.value)
        assertEquals("https://example.com/rss", repo.lastRefreshed)
    }

    @Test fun `failed refresh sets errorMessage and still clears isRefreshing`() = runTest {
        repo.refreshError = RuntimeException("network down")

        viewModel.refresh("https://example.com/rss")

        assertEquals("network down", viewModel.errorMessage.value)
        assertFalse(viewModel.isRefreshing.value)
    }

    // ── error surfacing ──────────────────────────────────────────────────────

    @Test fun `initial errorMessage is null`() {
        assertNull(viewModel.errorMessage.value)
    }

    @Test fun `failed subscribe sets errorMessage`() = runTest {
        repo.subscribeError = RuntimeException("boom")
        viewModel.subscribe(PodcastIndexFeed(id = 1, title = "X", feedUrl = "url"))
        assertEquals("boom", viewModel.errorMessage.value)
    }

    @Test fun `failed subscribeByUrl sets errorMessage`() = runTest {
        repo.subscribeByUrlError = RuntimeException("bad url")
        viewModel.subscribeByUrl("not-a-feed")
        assertEquals("bad url", viewModel.errorMessage.value)
    }

    @Test fun `failed addToQueue sets errorMessage`() = runTest {
        repo.addToQueueError = RuntimeException("db error")
        viewModel.addToQueue("guid1")
        assertEquals("db error", viewModel.errorMessage.value)
    }

    @Test fun `failed reorderQueue sets errorMessage`() = runTest {
        repo.reorderQueueError = RuntimeException("db error")
        viewModel.reorderQueue(emptyList())
        assertEquals("db error", viewModel.errorMessage.value)
    }

    @Test fun `failed toggleAutoQueue sets errorMessage`() = runTest {
        repo.toggleAutoQueueError = RuntimeException("db error")
        viewModel.toggleAutoQueue("url", true)
        assertEquals("db error", viewModel.errorMessage.value)
    }

    @Test fun `failed setDefaultSpeed sets errorMessage`() = runTest {
        repo.setDefaultSpeedError = RuntimeException("db error")
        viewModel.setDefaultSpeed("url", 1.5f)
        assertEquals("db error", viewModel.errorMessage.value)
    }

    @Test fun `clearErrorMessage resets to null`() = runTest {
        repo.subscribeError = RuntimeException("boom")
        viewModel.subscribe(PodcastIndexFeed(id = 1, title = "X", feedUrl = "url"))
        assertEquals("boom", viewModel.errorMessage.value)

        viewModel.clearErrorMessage()

        assertNull(viewModel.errorMessage.value)
    }

    @Test fun `successful subscribe does not set errorMessage`() = runTest {
        viewModel.subscribe(PodcastIndexFeed(id = 1, title = "X", feedUrl = "url"))
        assertNull(viewModel.errorMessage.value)
    }

    // ── subscriptions flow ───────────────────────────────────────────────────

    @Test fun `subscriptions reflects repo emissions`() = runTest(UnconfinedTestDispatcher()) {
        val collected = mutableListOf<List<Podcast>>()
        val job = launch { viewModel.subscriptions.collect { collected += it } }

        val podcasts = listOf(Podcast("url", "Title", null, null))
        repo.podcasts.value = podcasts

        assertEquals(podcasts, collected.last())
        job.cancel()
    }

    // ── episodes for feed ────────────────────────────────────────────────────

    @Test fun `episodesFor returns episodes from repo`() = runTest {
        val episode = com.rhamers.osse.model.Episode(
            guid = "guid1", podcastFeedUrl = "url", title = "Ep", enclosureUrl = "url.mp3",
            durationMs = 0, showNotes = null, pubDateMs = 0
        )
        repo.setEpisodes("url", listOf(episode))

        val result = viewModel.episodesFor("url").first()
        assertEquals(listOf(episode), result)
    }

    @Test fun `episodesFor returns empty list for unknown feed`() = runTest {
        val result = viewModel.episodesFor("unknown").first()
        assertTrue(result.isEmpty())
    }
}
