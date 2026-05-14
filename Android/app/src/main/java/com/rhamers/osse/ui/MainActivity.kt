package com.rhamers.osse.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.repository.DefaultPodcastRepository
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.player.MultiTrackService
import com.rhamers.osse.ui.podcast.EpisodeListScreen
import com.rhamers.osse.ui.podcast.PodcastSearchScreen
import com.rhamers.osse.ui.podcast.PodcastViewModel
import com.rhamers.osse.ui.theme.OsseTheme
import com.rhamers.osse.ui.tracks.TrackScreen
import com.rhamers.osse.ui.upnext.UpNextScreen

class MainActivity : ComponentActivity() {

    private var binder by mutableStateOf<MultiTrackService.LocalBinder?>(null)
    private var currentScreen by mutableStateOf<Screen>(Screen.Tracks)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as MultiTrackService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
    }

    private lateinit var podcastViewModel: PodcastViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = OsseDatabase.get(this)
        podcastViewModel = ViewModelProvider(
            this,
            PodcastViewModel.Factory(
                DefaultPodcastRepository(db.podcastDao(), db.episodeDao(), db.queueItemDao())
            )
        )[PodcastViewModel::class.java]

        setContent {
            OsseTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentScreen is Screen.Tracks,
                                onClick = { currentScreen = Screen.Tracks },
                                icon = { Icon(Icons.Default.Headphones, contentDescription = null) },
                                label = { Text("Now Playing") }
                            )
                            NavigationBarItem(
                                selected = currentScreen is Screen.PodcastSearch || currentScreen is Screen.EpisodeList,
                                onClick = { currentScreen = Screen.PodcastSearch },
                                icon = { Icon(Icons.Default.Podcasts, contentDescription = null) },
                                label = { Text("Podcasts") }
                            )
                            NavigationBarItem(
                                selected = currentScreen is Screen.UpNext,
                                onClick = { currentScreen = Screen.UpNext },
                                icon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null) },
                                label = { Text("Up Next") }
                            )
                        }
                    }
                ) { contentPadding ->
                    when (val screen = currentScreen) {
                        Screen.Tracks -> TrackScreen(binder, contentPadding)

                        Screen.PodcastSearch -> PodcastSearchScreen(
                            viewModel = podcastViewModel,
                            onPodcastClick = { podcast ->
                                currentScreen = Screen.EpisodeList(podcast.feedUrl, podcast.title)
                            },
                            contentPadding = contentPadding
                        )

                        is Screen.EpisodeList -> EpisodeListScreen(
                            feedUrl = screen.feedUrl,
                            podcastTitle = screen.podcastTitle,
                            viewModel = podcastViewModel,
                            onPlayEpisode = ::playEpisode,
                            onAddToQueue = { episode -> podcastViewModel.addToQueue(episode.guid) },
                            onBack = { currentScreen = Screen.PodcastSearch },
                            contentPadding = contentPadding
                        )

                        Screen.UpNext -> UpNextScreen(
                            viewModel = podcastViewModel,
                            onPlayEpisode = ::playEpisodeFromQueue,
                            onMarkComplete = { item -> binder?.markComplete(item.id, item.enclosureUrl) },
                            contentPadding = contentPadding
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MultiTrackService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
        binder = null
    }

    private fun playEpisode(episode: Episode) {
        val b = binder ?: return
        val track = b.getTrack(TrackId.PODCAST) ?: b.addTrack(TrackId.PODCAST)
        track.setSource(AudioSource.RssFeed(episode.enclosureUrl), episode.title)
        track.play()
        currentScreen = Screen.Tracks
    }

    private fun playEpisodeFromQueue(enclosureUrl: String, title: String) {
        val b = binder ?: return
        val track = b.getTrack(TrackId.PODCAST) ?: b.addTrack(TrackId.PODCAST)
        track.setSource(AudioSource.RssFeed(enclosureUrl), title)
        track.play()
        currentScreen = Screen.Tracks
    }
}
