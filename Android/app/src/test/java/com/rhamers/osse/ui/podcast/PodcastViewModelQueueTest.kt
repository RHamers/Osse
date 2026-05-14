package com.rhamers.osse.ui.podcast

import com.rhamers.osse.data.repository.FakePodcastRepository
import com.rhamers.osse.model.QueueItem
import com.rhamers.osse.util.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PodcastViewModelQueueTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repo: FakePodcastRepository
    private lateinit var viewModel: PodcastViewModel

    @Before
    fun setUp() {
        repo = FakePodcastRepository()
        viewModel = PodcastViewModel(repo)
    }

    // ── queue StateFlow ──────────────────────────────────────────────────────

    @Test fun `queue is empty initially`() {
        assertTrue(viewModel.queue.value.isEmpty())
    }

    @Test fun `queue reflects repo emissions`() = runTest(UnconfinedTestDispatcher()) {
        val collected = mutableListOf<List<QueueItem>>()
        val job = launch { viewModel.queue.collect { collected += it } }

        val items = listOf(item(id = 1, guid = "ep1", title = "Ep 1", podcast = "Pod A"))
        repo.queue.value = items

        assertEquals(items, collected.last())
        job.cancel()
    }

    // ── addToQueue ───────────────────────────────────────────────────────────

    @Test fun `addToQueue delegates guid to repo`() = runTest {
        viewModel.addToQueue("ep-guid-123")
        assertTrue(repo.queuedGuids.contains("ep-guid-123"))
    }

    @Test fun `addToQueue can be called multiple times for different guids`() = runTest {
        viewModel.addToQueue("ep1")
        viewModel.addToQueue("ep2")
        assertEquals(listOf("ep1", "ep2"), repo.queuedGuids)
    }

    // ── removeFromQueue ──────────────────────────────────────────────────────

    @Test fun `removeFromQueue delegates id to repo`() = runTest {
        viewModel.removeFromQueue(42L)
        assertTrue(repo.removedQueueIds.contains(42L))
    }

    // ── reorderQueue ─────────────────────────────────────────────────────────

    @Test fun `reorderQueue delegates the reordered list to repo`() = runTest {
        val items = listOf(
            item(id = 1, guid = "ep1", title = "One", podcast = "Pod"),
            item(id = 2, guid = "ep2", title = "Two", podcast = "Pod")
        )
        viewModel.reorderQueue(items)
        assertEquals(items, repo.reorderedQueue)
    }

    // ── toggleAutoQueue ──────────────────────────────────────────────────────

    @Test fun `toggleAutoQueue with false delegates to repo`() = runTest {
        viewModel.toggleAutoQueue("https://feed.com/rss", false)
        assertEquals("https://feed.com/rss" to false, repo.lastAutoQueueToggle)
    }

    @Test fun `toggleAutoQueue with true delegates to repo`() = runTest {
        viewModel.toggleAutoQueue("https://feed.com/rss", true)
        assertEquals("https://feed.com/rss" to true, repo.lastAutoQueueToggle)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun item(
        id: Long,
        guid: String,
        title: String,
        podcast: String,
        position: Int = 0,
        durationMs: Long = 0L
    ) = QueueItem(id, position, guid, title, podcast, durationMs, "https://ex.com/$guid.mp3")
}
