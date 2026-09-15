package com.example.gbpusdpredictor.network

import com.example.gbpusdpredictor.network.RetrofitClient
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class FredObservation(val date: String, val value: String)
data class FredResponse(val observations: List<FredObservation>)

interface FREDService {
    @GET("fred/series/observations")
    suspend fun getSeries(
        @Query("series_id") seriesId: String,
        @Query("api_key") apiKey: String,
        @Query("file_type") fileType: String = "json",
        @Query("sort_order") sortOrder: String = "desc",
        @Query("limit") limit: Int = 5,
        // Bounds the query to a specific window -- used to fetch "the observation for
        // exactly this trading date" (plus a small lookback buffer) instead of just
        // "whatever's most recent," which is what let a stale-but-still-latest reading
        // get silently labeled as today's. Both optional/nullable so existing callers
        // that just want the most recent N observations are unaffected.
        @Query("observation_start") observationStart: String? = null,
        @Query("observation_end") observationEnd: String? = null
    ): FredResponse
}

object FredApi {
    val service: FREDService by lazy {
        RetrofitClient.create(
            baseUrl = "https://api.stlouisfed.org/",
            converterFactory = GsonConverterFactory.create(),
            serviceClass = FREDService::class.java
        )
    }
}
