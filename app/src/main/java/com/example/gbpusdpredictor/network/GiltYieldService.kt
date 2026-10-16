package com.example.gbpusdpredictor.network

import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * The user's own free, self-maintained UK 2yr gilt yield snapshot at
 * github.com/andypeak84/gilt-yield-fetcher/data/gilt-2y.json.
 *
 * Shape is genuinely uncertain and expected to change: it started as a single current-day
 * object -- {"date": "2026-07-30", "yield_2y": 4.26} -- but is designed to eventually hold
 * a rolling 16-day array of the same shape instead, once it finishes accumulating. Fetched
 * as raw text (not a typed Gson response) specifically so both shapes can be parsed --
 * see MarketRepository.fetchGiltYieldHistory, which reads out whichever entry's OWN date
 * matches the target date, regardless of which shape the file is currently in.
 */
interface GiltYieldService {
    // See OilPriceService.kt's own comment -- same raw.githubusercontent.com Fastly CDN,
    // which ignores a client-sent Cache-Control request header for its own caching
    // decision (confirmed the hard way on the oil/Brent endpoints, 2026-10: 5 days
    // stuck on one stale snapshot despite the committed file and the GitHub Action both
    // being fine). This endpoint hasn't shown the symptom yet, but it's the identical
    // CDN and identical risk, so the same cacheBust fix applies pre-emptively.
    @Headers("Cache-Control: no-cache")
    @GET("andypeak84/gilt-yield-fetcher/main/data/gilt-2y.json")
    suspend fun getLatestRaw(@Query("cacheBust") cacheBust: Long = System.currentTimeMillis()): String
}

object GiltYieldApi {
    val service: GiltYieldService by lazy {
        RetrofitClient.create(
            baseUrl = "https://raw.githubusercontent.com/",
            converterFactory = ScalarsConverterFactory.create(),
            serviceClass = GiltYieldService::class.java
        )
    }
}
