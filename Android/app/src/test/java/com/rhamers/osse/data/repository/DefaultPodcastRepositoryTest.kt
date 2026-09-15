package com.rhamers.osse.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rhamers.osse.data.api.PodcastByUrlResponse
import com.rhamers.osse.data.api.PodcastIndexApi
import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.data.api.SearchResponse
import com.rhamers.osse.data.db.OsseDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultPodcastRepositoryTest {

    private lateinit var db: OsseDatabase
    private lateinit var server: MockWebServer
    private val feedBodies = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = feedBodies[request.path] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun repository(api: PodcastIndexApi = fakeApi()) =
        DefaultPodcastRepository(db.podcastDao(), db.episodeDao(), db.queueItemDao(), api)

    private fun feedUrl(path: String) = server.url(path).toString()

    private fun serveFeed(path: String, xml: String) {
        feedBodies[path] = xml
    }

    private fun fakeApi(feeds: List<PodcastIndexFeed> = emptyList()) = object : PodcastIndexApi {
        override suspend fun searchPodcasts(query: String, max: Int) = SearchResponse(feeds = feeds)
        override suspend fun getPodcastByUrl(feedUrl: String) = PodcastByUrlResponse()
    }

    private fun rss(title: String, itemsXml: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>$title</title>
            $itemsXml
          </channel>
        </rss>
    """.trimIndent()

    // Single-line on purpose: embedding a multi-line, independently-trimIndented
    // fragment into rss()'s template would drag rss()'s own trimIndent() calculation
    // down to this fragment's near-zero indentation, leaving stray leading whitespace
    // before rss()'s <?xml ...?> declaration — which XML requires to be the very first
    // character in the document.
    private fun item(guid: String, pubDate: String) =
        "<item><title>Episode $guid</title><guid>$guid</guid>" +
            "<enclosure url=\"https://example.com/$guid.mp3\" type=\"audio/mpeg\" length=\"0\"/>" +
            "<pubDate>$pubDate</pubDate></item>"

    // A pubDate safely in the past relative to any subscribedAt set during a test run.
    private val pastDate = "Mon, 01 Jan 2024 00:00:00 GMT"

    // A pubDate safely in the future relative to any subscribedAt set during a test run.
    private val futureDate = "Fri, 01 Jan 2027 00:00:00 GMT"

    // ── subscribe ────────────────────────────────────────────────────────────

    @Test
    fun `subscribe stores podcast using RSS title and fetches episodes`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("My Podcast", item("ep1", pastDate)))

        repository().subscribe(PodcastIndexFeed(title = "Ignored Search Title", feedUrl = url))

        val podcast = db.podcastDao().getByUrl(url)
        assertNotNull(podcast)
        assertEquals("My Podcast", podcast!!.title)
        assertNotNull(db.episodeDao().getByGuid("ep1"))
    }

    @Test
    fun `subscribe does not auto-queue episodes that already existed in the feed`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))

        repository().subscribe(PodcastIndexFeed(feedUrl = url))

        assertTrue(db.queueItemDao().getAllGuids().isEmpty())
    }

    // ── subscribeByUrl ───────────────────────────────────────────────────────

    @Test
    fun `subscribeByUrl creates a podcast entity and fetches episodes`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))

        repository().subscribeByUrl(url)

        assertNotNull(db.podcastDao().getByUrl(url))
        assertNotNull(db.episodeDao().getByGuid("ep1"))
    }

    @Test
    fun `subscribeByUrl does not reset an already-subscribed podcast`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)
        val subscribedAt = db.podcastDao().getByUrl(url)!!.subscribedAt

        serveFeed("/feed", rss("Pod", item("ep1", pastDate) + item("ep2", futureDate)))
        repo.subscribeByUrl(url)

        assertEquals(subscribedAt, db.podcastDao().getByUrl(url)!!.subscribedAt)
    }

    // ── refreshEpisodes ──────────────────────────────────────────────────────

    @Test
    fun `refreshEpisodes updates feed metadata while preserving local podcast state`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Old Title", item("ep1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)
        db.podcastDao().setAutoQueue(url, false)
        db.podcastDao().setDefaultSpeed(url, 1.5f)

        serveFeed("/feed", rss("New Title", item("ep1", pastDate)))
        repo.refreshEpisodes(url)

        val podcast = db.podcastDao().getByUrl(url)!!
        assertEquals("New Title", podcast.title)
        assertFalse(podcast.autoQueue)
        assertEquals(1.5f, podcast.defaultSpeed, 0.001f)
    }

    @Test
    fun `refreshEpisodes auto-queues only episodes published after subscribedAt`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("old", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)

        serveFeed("/feed", rss("Pod", item("old", pastDate) + item("new", futureDate)))
        repo.refreshEpisodes(url)

        assertEquals(listOf("new"), db.queueItemDao().getAllGuids())
    }

    @Test
    fun `refreshEpisodes does not auto-queue when autoQueue is disabled`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("old", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)
        db.podcastDao().setAutoQueue(url, false)

        serveFeed("/feed", rss("Pod", item("old", pastDate) + item("new", futureDate)))
        repo.refreshEpisodes(url)

        assertTrue(db.queueItemDao().getAllGuids().isEmpty())
    }

    @Test
    fun `refreshEpisodes does not re-queue an episode already in the queue`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("old", pastDate) + item("new", futureDate)))
        val repo = repository()
        repo.subscribeByUrl(url)
        repo.refreshEpisodes(url)
        assertEquals(1, db.queueItemDao().getAllGuids().size)

        repo.refreshEpisodes(url)

        assertEquals(1, db.queueItemDao().getAllGuids().size)
    }

    // ── refreshAllPodcasts ───────────────────────────────────────────────────

    @Test
    fun `refreshAllPodcasts refreshes every subscribed podcast`() = runBlocking {
        val urlA = feedUrl("/a")
        val urlB = feedUrl("/b")
        serveFeed("/a", rss("Podcast A", item("a1", pastDate)))
        serveFeed("/b", rss("Podcast B", item("b1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(urlA)
        repo.subscribeByUrl(urlB)

        serveFeed("/a", rss("Podcast A v2", item("a1", pastDate)))
        serveFeed("/b", rss("Podcast B v2", item("b1", pastDate)))
        repo.refreshAllPodcasts()

        assertEquals("Podcast A v2", db.podcastDao().getByUrl(urlA)!!.title)
        assertEquals("Podcast B v2", db.podcastDao().getByUrl(urlB)!!.title)
    }

    @Test
    fun `refreshAllPodcasts continues past a feed that fails`() = runBlocking {
        // Subscribe while both servers are reachable, then take one down so only the
        // later refreshAllPodcasts() call (not subscribeByUrl) observes the failure.
        val failingServer = MockWebServer()
        failingServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody(rss("Dead Podcast", item("d1", pastDate)))
        }
        failingServer.start()
        val failingUrl = failingServer.url("/dead").toString()

        val goodUrl = feedUrl("/good")
        serveFeed("/good", rss("Good Podcast", item("g1", pastDate)))

        val repo = repository()
        repo.subscribeByUrl(failingUrl)
        repo.subscribeByUrl(goodUrl)

        failingServer.shutdown() // now unreachable: connection refused on refresh
        serveFeed("/good", rss("Good Podcast v2", item("g1", pastDate)))
        repo.refreshAllPodcasts() // must not throw despite failingUrl being unreachable

        assertEquals("Good Podcast v2", db.podcastDao().getByUrl(goodUrl)!!.title)
    }

    // ── unsubscribe ──────────────────────────────────────────────────────────

    @Test
    fun `unsubscribe removes the podcast and cascades its episodes`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)
        repo.unsubscribe(url)

        assertNull(db.podcastDao().getByUrl(url))
        assertNull(db.episodeDao().getByGuid("ep1"))
    }

    @Test
    fun `unsubscribe is a no-op for an unknown feed url`() = runBlocking {
        repository().unsubscribe("https://not-subscribed.example.com/feed")
        // must not throw
    }

    // ── queue management ─────────────────────────────────────────────────────

    @Test
    fun `addToQueue appends at the end and ignores duplicates`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate) + item("ep2", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)

        repo.addToQueue("ep1")
        repo.addToQueue("ep2")
        repo.addToQueue("ep1") // duplicate, ignored

        assertEquals(listOf("ep1", "ep2"), db.queueItemDao().getAllGuids())
    }

    @Test
    fun `removeFromQueue deletes the item by id`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)
        repo.addToQueue("ep1")
        val id = db.queueItemDao().peekFirst()!!.id

        repo.removeFromQueue(id)

        assertTrue(db.queueItemDao().getAllGuids().isEmpty())
    }

    @Test
    fun `reorderQueue rewrites positions to match the given order`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate) + item("ep2", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)
        repo.addToQueue("ep1")
        repo.addToQueue("ep2")

        val current = repo.observeQueue().first()
        repo.reorderQueue(current.reversed())

        assertEquals(listOf("ep2", "ep1"), repo.observeQueue().first().map { it.episodeGuid })
    }

    // ── misc ──────────────────────────────────────────────────────────────────

    @Test
    fun `toggleAutoQueue flips the autoQueue flag`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)

        repo.toggleAutoQueue(url, false)
        assertFalse(db.podcastDao().getByUrl(url)!!.autoQueue)

        repo.toggleAutoQueue(url, true)
        assertTrue(db.podcastDao().getByUrl(url)!!.autoQueue)
    }

    @Test
    fun `markEpisodeCompleted sets the completed flag`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)

        repo.markEpisodeCompleted("ep1")

        assertTrue(db.episodeDao().getByGuid("ep1")!!.completed)
    }

    @Test
    fun `updateEpisodePosition persists the position`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)

        repo.updateEpisodePosition("ep1", 42_000L)

        assertEquals(42_000L, db.episodeDao().getByGuid("ep1")!!.positionMs)
    }

    @Test
    fun `setDefaultSpeed clamps to the 1x to 2x range`() = runBlocking {
        val url = feedUrl("/feed")
        serveFeed("/feed", rss("Pod", item("ep1", pastDate)))
        val repo = repository()
        repo.subscribeByUrl(url)

        repo.setDefaultSpeed(url, 3.0f)
        assertEquals(2.0f, db.podcastDao().getByUrl(url)!!.defaultSpeed, 0.001f)

        repo.setDefaultSpeed(url, 0.2f)
        assertEquals(1.0f, db.podcastDao().getByUrl(url)!!.defaultSpeed, 0.001f)
    }

    @Test
    fun `importOpml subscribes to every feed url`() = runBlocking {
        val urlA = feedUrl("/a")
        val urlB = feedUrl("/b")
        serveFeed("/a", rss("Podcast A", item("a1", pastDate)))
        serveFeed("/b", rss("Podcast B", item("b1", pastDate)))

        repository().importOpml(listOf(urlA, urlB))

        assertNotNull(db.podcastDao().getByUrl(urlA))
        assertNotNull(db.podcastDao().getByUrl(urlB))
    }

    @Test
    fun `searchPodcasts delegates to the injected api`() = runBlocking {
        val feeds = listOf(PodcastIndexFeed(id = 1, title = "Result", feedUrl = "https://example.com/feed"))
        val results = repository(api = fakeApi(feeds)).searchPodcasts("query")

        assertEquals(feeds, results)
    }
}
