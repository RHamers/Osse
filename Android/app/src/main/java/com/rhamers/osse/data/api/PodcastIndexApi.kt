package com.rhamers.osse.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface PodcastIndexApi {
    @GET("search/byterm")
    suspend fun searchPodcasts(
        @Query("q") query: String,
        @Query("max") max: Int = 20
    ): SearchResponse

    @GET("podcasts/byfeedurl")
    suspend fun getPodcastByUrl(@Query("url") feedUrl: String): PodcastByUrlResponse
}

data class SearchResponse(
    @SerializedName("feeds") val feeds: List<PodcastIndexFeed> = emptyList(),
    @SerializedName("status") val status: String = ""
)

data class PodcastByUrlResponse(
    @SerializedName("feed") val feed: PodcastIndexFeed? = null,
    @SerializedName("status") val status: String = ""
)

data class PodcastIndexFeed(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("url") val feedUrl: String = "",
    @SerializedName("image") val imageUrl: String? = null,
    @SerializedName("description") val description: String? = null
)
