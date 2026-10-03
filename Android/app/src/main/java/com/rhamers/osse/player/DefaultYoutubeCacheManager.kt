package com.rhamers.osse.player

import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.db.YoutubeCacheEntity
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeExtractor
import com.rhamers.osse.youtube.YoutubeVideoMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class DefaultYoutubeCacheManager(
    private val extractor: YoutubeExtractor,
    private val cacheDir: File,
    private val db: OsseDatabase,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val downloadThumbnail: suspend (url: String, destPath: String) -> Unit = ::httpDownloadFile,
    private val downloadAudioFile: suspend (streamUrl: String, destPath: String) -> Unit,
    private val onCached: ((streamUrl: String, localPath: String) -> Unit)? = null,
) : YoutubeCacheManager {

    private val inFlightMutex = Mutex()
    private val inFlight = mutableMapOf<String, Job>()

    override suspend fun resolveSource(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val cached = withContext(ioDispatcher) { db.youtubeCacheDao().getById(videoId) }
        if (cached != null) return AudioSource.YoutubeDownloaded(cached.localPath)

        val streamUrl = withContext(ioDispatcher) { extractor.extractAudioUrl(videoUrl) }
        scope.launch {
            inFlightMutex.withLock {
                if (inFlight.containsKey(videoId)) return@launch
                inFlight[videoId] = coroutineContext[Job]!!
            }
            try {
                val local = saveToCache(videoId, videoUrl, metadata, knownStreamUrl = streamUrl) as AudioSource.YoutubeDownloaded
                onCached?.invoke(streamUrl, local.localPath)
            } catch (_: Exception) {
            } finally {
                inFlightMutex.withLock { inFlight.remove(videoId) }
            }
        }
        return AudioSource.YoutubeStream(streamUrl)
    }

    override suspend fun downloadAndCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        val cached = withContext(ioDispatcher) { db.youtubeCacheDao().getById(videoId) }
        if (cached != null) return AudioSource.YoutubeDownloaded(cached.localPath)
        return saveToCache(videoId, videoUrl, metadata)
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        extractor.searchVideos(query, maxResults)

    override suspend fun delete(videoId: String) {
        withContext(ioDispatcher) {
            val entity = db.youtubeCacheDao().getById(videoId)
            entity?.let {
                File(it.localPath).delete()
                File(it.thumbnailPath).delete()
            }
            db.youtubeCacheDao().delete(videoId)
        }
    }

    private suspend fun saveToCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
        knownStreamUrl: String? = null,
    ): AudioSource {
        val audioPath = File(cacheDir, "$videoId.webm").absolutePath
        val thumbPath = File(cacheDir, "$videoId.jpg").absolutePath
        try {
            val streamUrl = knownStreamUrl ?: withContext(ioDispatcher) { extractor.extractAudioUrl(videoUrl) }
            withContext(ioDispatcher) { downloadAudioFile(streamUrl, audioPath) }
            downloadThumbnail(metadata.thumbnailUrl, thumbPath)
            withContext(ioDispatcher) {
                db.youtubeCacheDao().insert(
                    YoutubeCacheEntity(
                        videoId = videoId,
                        title = metadata.title,
                        channel = metadata.channel,
                        durationSecs = metadata.durationSecs,
                        thumbnailPath = thumbPath,
                        localPath = audioPath,
                        cachedAt = System.currentTimeMillis(),
                    )
                )
            }
            return AudioSource.YoutubeDownloaded(audioPath)
        } catch (e: Exception) {
            File(audioPath).delete()
            File(thumbPath).delete()
            throw e
        }
    }
}

// internal (not private) so MultiTrackService can reuse it instead of constructing a
// redundant second OkHttpClient for its own NewPipeExtractor downloader.
internal val sharedHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private suspend fun httpGetToFile(url: String, destPath: String, userAgent: String?) {
    withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        if (userAgent != null) requestBuilder.header("User-Agent", userAgent)
        sharedHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading $url")
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            response.body?.byteStream()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Empty response body for $url")
        }
    }
}

private suspend fun httpDownloadFile(url: String, destPath: String) = httpGetToFile(url, destPath, userAgent = null)

// Downloading a googlevideo audio stream requires the same User-Agent that was used to
// obtain it (see YoutubeExtractor.playbackUserAgent()) — module-visible (not file-private)
// so MultiTrackService can bind it with NewPipeYoutubeExtractor.playbackUserAgent().
internal suspend fun httpDownloadAudioFile(url: String, destPath: String, userAgent: String) =
    httpGetToFile(url, destPath, userAgent)
