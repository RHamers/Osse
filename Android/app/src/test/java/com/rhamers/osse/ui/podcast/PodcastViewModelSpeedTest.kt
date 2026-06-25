package com.rhamers.osse.ui.podcast

import com.rhamers.osse.data.repository.FakePodcastRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastViewModelSpeedTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: FakePodcastRepository
    private lateinit var viewModel: PodcastViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakePodcastRepository()
        viewModel = PodcastViewModel(repo)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `setDefaultSpeed delegates to repository`() = runTest {
        viewModel.setDefaultSpeed("https://feed.com/rss", 1.5f)
        advanceUntilIdle()
        assertEquals("https://feed.com/rss" to 1.5f, repo.lastDefaultSpeed)
    }
}
