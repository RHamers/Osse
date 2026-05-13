package com.rhamers.osse.data.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest

object PodcastIndexClient {

    val api: PodcastIndexApi by lazy {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor())
            .addInterceptor(logging)
            .build()
        Retrofit.Builder()
            .baseUrl(PodcastIndexConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PodcastIndexApi::class.java)
    }

    private fun authInterceptor() = Interceptor { chain ->
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val hash = sha1(PodcastIndexConfig.API_KEY + PodcastIndexConfig.API_SECRET + timestamp)
        val req = chain.request().newBuilder()
            .addHeader("X-Auth-Key", PodcastIndexConfig.API_KEY)
            .addHeader("X-Auth-Date", timestamp)
            .addHeader("Authorization", hash)
            .addHeader("User-Agent", "Osse/0.1")
            .build()
        chain.proceed(req)
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
