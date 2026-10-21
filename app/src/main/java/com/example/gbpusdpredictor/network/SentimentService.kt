package com.example.gbpusdpredictor.network

import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * The GBP/USD news-sentiment signal at github.com/andypeak84/Oilfetcher/data/sentiment.json,
 * written hourly by that repo's publish-sentiment Action (scripts/publish_sentiment.py, which
 * runs the gbpusd-sentiment pipeline: RSS -> score -> time-decayed 1h/6h/24h aggregate).
 *
 * Fetched as raw text and parsed by SentimentRepository (org.json), same reasoning as
 * OilPriceService. cacheBust: raw.githubusercontent.com's Fastly CDN ignores a client
 * Cache-Control request header -- see OilPriceService's own comment for the 5-day-stale
 * incident that proved it -- so a fresh query value on every call is what actually forces
 * a fresh copy.
 */
interface SentimentService {
    @Headers("Cache-Control: no-cache")
    @GET("andypeak84/Oilfetcher/main/data/sentiment.json")
    suspend fun getLatestRaw(@Query("cacheBust") cacheBust: Long = System.currentTimeMillis()): String
}

object SentimentApi {
    val service: SentimentService by lazy {
        RetrofitClient.create(
            baseUrl = "https://raw.githubusercontent.com/",
            converterFactory = ScalarsConverterFactory.create(),
            serviceClass = SentimentService::class.java
        )
    }
}
