package com.rhamers.osse.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.VideoLibrary
import com.rhamers.osse.ui.youtube.YoutubeLibraryScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.repository.DefaultPodcastRepository
import com.rhamers.osse.debug.LogExporter
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.model.TrackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rhamers.osse.player.MultiTrackService
import com.rhamers.osse.player.TrackPlayer
import com.rhamers.osse.ui.podcast.EpisodeDetailScreen
import com.rhamers.osse.ui.podcast.EpisodeListScreen
import com.rhamers.osse.ui.podcast.PodcastSearchScreen
import com.rhamers.osse.ui.podcast.PodcastViewModel
import com.rhamers.osse.ui.share.ReleaseQrScreen
import com.rhamers.osse.ui.theme.OsseTheme
import com.rhamers.osse.ui.tracks.TrackScreen
import com.rhamers.osse.ui.upnext.UpNextScreen

class MainActivity : ComponentActivity() {

    private var binder by mutableStateOf<MultiTrackService.LocalBinder?>(null)
    private var currentScreen by mutableStateOf<Screen>(Screen.Tracks)

    private var pendingTrackId = TrackId.PODCAST
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var opmlPickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var opmlExportLauncher: ActivityResultLauncher<String>

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as MultiTrackService.LocalBinder
        }
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
    }

    private lateinit var podcastViewModel: PodcastViewModel

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on API 33+ so Media3's foreground notification shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // Bind in onCreate() / unbind in onDestroy() so the service stays bound while the
        // activity exists (even when backgrounded). This prevents MediaSessionService from
        // tearing down its foreground notification management on every onStop().
        val serviceIntent = Intent(this, MultiTrackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

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

        opmlExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    podcastViewModel.exportOpml(stream)
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
                val snackbarHostState = remember { SnackbarHostState() }
                val errorMessage by podcastViewModel.errorMessage.collectAsState()
                LaunchedEffect(errorMessage) {
                    val message = errorMessage ?: return@LaunchedEffect
                    snackbarHostState.showSnackbar(message)
                    podcastViewModel.clearErrorMessage()
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Ossë") },
                            actions = {
                                IconButton(onClick = {
                                    lifecycleScope.launch {
                                        val file = withContext(Dispatchers.IO) { LogExporter.export(this@MainActivity) }
                                        snackbarHostState.showSnackbar("Logs exported to ${file.name}")
                                    }
                                }) {
                                    Icon(Icons.Default.BugReport, contentDescription = "Export logs")
                                }
                                IconButton(onClick = { currentScreen = Screen.ReleaseQr }) {
                                    Icon(Icons.Default.QrCode2, contentDescription = "Show release QR code")
                                }
                            }
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            NavigationBarItem(
                                selected = currentScreen is Screen.YoutubeLibrary,
                                onClick = { currentScreen = Screen.YoutubeLibrary },
                                icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                                label = { Text("Library") }
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
                            onExportOpml = { opmlExportLauncher.launch("osse-subscriptions.opml") },
                            snackbarHostState = snackbarHostState,
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
                            onSetDefaultSpeed = { speed ->
                                podcastViewModel.setDefaultSpeed(screen.feedUrl, speed)
                            },
                            onSetAutoQueue = { enabled ->
                                podcastViewModel.toggleAutoQueue(screen.feedUrl, enabled)
                            },
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

                        Screen.UpNext -> {
                            val podcastTrackState by remember(binder) {
                                binder?.getTrack(TrackId.PODCAST)?.state ?: MutableStateFlow(TrackState())
                            }.collectAsState()
                            val currentPlayingUrl =
                                (podcastTrackState.source as? AudioSource.RssFeed)?.enclosureUrl
                            UpNextScreen(
                                viewModel = podcastViewModel,
                                onPlayEpisode = ::playEpisodeFromQueue,
                                onMarkComplete = { item -> binder?.markComplete(item.id, item.enclosureUrl) },
                                currentPlayingUrl = currentPlayingUrl,
                                contentPadding = contentPadding
                            )
                        }

                        Screen.ReleaseQr -> ReleaseQrScreen(
                            onBack = { currentScreen = Screen.Tracks },
                            contentPadding = contentPadding
                        )

                        Screen.YoutubeLibrary -> YoutubeLibraryScreen(
                            cachedVideos = OsseDatabase.get(this).youtubeCacheDao().getAll(),
                            onPlay = { entity ->
                                val b = binder ?: return@YoutubeLibraryScreen
                                b.getTrack(TrackId.MUSIC)?.apply {
                                    setSource(AudioSource.YoutubeDownloaded(entity.localPath))
                                    play()
                                }
                            },
                            onDelete = { videoId ->
                                lifecycleScope.launch {
                                    binder?.cacheManager?.delete(videoId)
                                }
                            },
                            contentPadding = contentPadding,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(serviceConnection)
        binder = null
    }

    // Applies the podcast's saved defaultSpeed and resumes from positionMs — the two
    // playback defaults every podcast-episode play path must apply before track.play().
    private fun applyPlaybackDefaults(track: TrackPlayer, podcastFeedUrl: String, positionMs: Long) {
        val podcast = podcastViewModel.subscriptions.value.find { it.feedUrl == podcastFeedUrl }
        podcast?.let { track.setSpeed(it.defaultSpeed) }
        if (positionMs > 0) track.seekTo(positionMs)
    }

    private fun playEpisode(episode: Episode) {
        val b = binder ?: return
        val track = b.getTrack(pendingTrackId) ?: b.addTrack(pendingTrackId)
        track.setSource(AudioSource.RssFeed(episode.enclosureUrl), episode.title)
        applyPlaybackDefaults(track, episode.podcastFeedUrl, episode.positionMs)
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
        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) {
                OsseDatabase.get(this@MainActivity).episodeDao().getByEnclosureUrl(enclosureUrl)
            }
            val currentTrack = binder?.getTrack(TrackId.PODCAST) ?: return@launch
            entity?.let { applyPlaybackDefaults(currentTrack, it.podcastFeedUrl, it.positionMs) }
        }
    }
}
