package com.example.gbpusdpredictor.network

import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * The GBP/USD news-sentiment signal at github.com/andypeak84/sentiment/data/sentiment.json,
 * written once a day (06:00 UTC) by that repo's publish-sentiment Action (scripts/
 * publish_sentiment.py: RSS -> score -> same-story merge -> time-decayed 1h/6h/24h aggregate).
 *
 * Fetched as raw text and parsed by SentimentRepository (org.json), same reasoning as
 * OilPriceService. cacheBust: raw.githubusercontent.com's Fastly CDN ignores a client
 * Cache-Control request header -- see OilPriceService's own comment for the 5-day-stale
 * incident that proved it -- so a fresh query value on every call is what actually forces
 * a fresh copy.
 */
interface SentimentService {
    @Headers("Cache-Control: no-cache")
    @GET("andypeak84/sentiment/main/data/sentiment.json")
    suspend fun getLatestRaw(@Query("cacheBust") cacheBust: Long = System.currentTimeMillis()): String

    // One point per publisher run: [{"ts": epoch seconds, "s24h": score, "n24h": stories, ...}]. Used by the
    // Accuracy screen to score forecasts WITH the news overlay, using the reading that existed at the time.
    @Headers("Cache-Control: no-cache")
    @GET("andypeak84/sentiment/main/data/sentiment-history.json")
    suspend fun getHistoryRaw(@Query("cacheBust") cacheBust: Long = System.currentTimeMillis()): String
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
