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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.lifecycleScope
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.repository.DefaultPodcastRepository
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rhamers.osse.player.MultiTrackService
import com.rhamers.osse.ui.podcast.EpisodeDetailScreen
import com.rhamers.osse.ui.podcast.EpisodeListScreen
import com.rhamers.osse.ui.podcast.PodcastSearchScreen
import com.rhamers.osse.ui.podcast.PodcastViewModel
import com.rhamers.osse.ui.theme.OsseTheme
import com.rhamers.osse.ui.tracks.TrackScreen
import com.rhamers.osse.ui.upnext.UpNextScreen

class MainActivity : ComponentActivity() {

    private var binder by mutableStateOf<MultiTrackService.LocalBinder?>(null)
    private var currentScreen by mutableStateOf<Screen>(Screen.Tracks)

    private var pendingTrackId = TrackId.PODCAST
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var opmlPickerLauncher: ActivityResultLauncher<Array<String>>

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

        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                binder?.getTrack(pendingTrackId)?.setSource(AudioSource.LocalFile(uri))
            }
        }

        opmlPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    podcastViewModel.importOpml(stream)
                }
            }
        }

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
                                selected = currentScreen is Screen.PodcastSearch || currentScreen is Screen.EpisodeList ||
                            (currentScreen is Screen.EpisodeDetail && (currentScreen as Screen.EpisodeDetail).backScreen !is Screen.Tracks),
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
                        Screen.Tracks -> TrackScreen(
                            binder = binder,
                            contentPadding = contentPadding,
                            onSelectPodcast = { trackId ->
                                pendingTrackId = trackId
                                currentScreen = Screen.PodcastSearch
                            },
                            onSelectLocalFile = { trackId ->
                                pendingTrackId = trackId
                                filePickerLauncher.launch(arrayOf("audio/*"))
                            },
                            onEpisodeTitleClick = { trackId -> openEpisodeDetailFromPlayer(trackId) }
                        )

                        Screen.PodcastSearch -> PodcastSearchScreen(
                            viewModel = podcastViewModel,
                            onPodcastClick = { podcast ->
                                currentScreen = Screen.EpisodeList(podcast.feedUrl, podcast.title)
                            },
                            onImportOpml = { opmlPickerLauncher.launch(arrayOf("text/*", "application/xml", "*/*")) },
                            contentPadding = contentPadding
                        )

                        is Screen.EpisodeList -> EpisodeListScreen(
                            feedUrl = screen.feedUrl,
                            podcastTitle = screen.podcastTitle,
                            viewModel = podcastViewModel,
                            onPlayEpisode = ::playEpisode,
                            onAddToQueue = { episode -> podcastViewModel.addToQueue(episode.guid) },
                            onBack = { currentScreen = Screen.PodcastSearch },
                            onEpisodeClick = { episode -> currentScreen = Screen.EpisodeDetail(episode, screen) },
                            contentPadding = contentPadding
                        )

                        is Screen.EpisodeDetail -> EpisodeDetailScreen(
                            episode = screen.episode,
                            onPlay = { episode ->
                                playEpisode(episode)
                            },
                            onAddToQueue = { episode -> podcastViewModel.addToQueue(episode.guid) },
                            onBack = { currentScreen = screen.backScreen },
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
        val track = b.getTrack(pendingTrackId) ?: b.addTrack(pendingTrackId)
        track.setSource(AudioSource.RssFeed(episode.enclosureUrl), episode.title)
        track.play()
        currentScreen = Screen.Tracks
    }

    private fun openEpisodeDetailFromPlayer(trackId: TrackId) {
        val track = binder?.getTrack(trackId) ?: return
        val enclosureUrl = (track.state.value.source as? AudioSource.RssFeed)?.enclosureUrl ?: return
        lifecycleScope.launch {
            val db = OsseDatabase.get(this@MainActivity)
            val entity = withContext(Dispatchers.IO) { db.episodeDao().getByEnclosureUrl(enclosureUrl) } ?: return@launch
            val episode = Episode(
                guid = entity.guid,
                podcastFeedUrl = entity.podcastFeedUrl,
                title = entity.title,
                enclosureUrl = entity.enclosureUrl,
                durationMs = entity.durationMs,
                showNotes = entity.showNotes,
                pubDateMs = entity.pubDateMs,
                positionMs = entity.positionMs,
                completed = entity.completed
            )
            currentScreen = Screen.EpisodeDetail(episode, Screen.Tracks)
        }
    }

    private fun playEpisodeFromQueue(enclosureUrl: String, title: String) {
        val b = binder ?: return
        val track = b.getTrack(TrackId.PODCAST) ?: b.addTrack(TrackId.PODCAST)
        track.setSource(AudioSource.RssFeed(enclosureUrl), title)
        track.play()
        currentScreen = Screen.Tracks
    }
}
