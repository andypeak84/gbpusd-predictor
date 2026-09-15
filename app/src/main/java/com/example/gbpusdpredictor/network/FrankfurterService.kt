package com.example.gbpusdpredictor.network

import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class FrankfurterResponse(val date: String, val rates: Map<String, Double>)

// rates: date string ("2026-07-01") -> {"USD": 1.3423}. Frankfurter's own free, real,
// unauthenticated historical range endpoint -- the training script already relies on this
// exact endpoint, so it's proven data, not a new dependency.
data class FrankfurterTimeSeriesResponse(val start_date: String, val end_date: String, val rates: Map<String, Map<String, Double>>)

interface FrankfurterService {
    @GET("v1/latest")
    suspend fun getLatest(
        @Query("base") base: String = "GBP",
        @Query("symbols") symbols: String = "USD"
    ): FrankfurterResponse

    @GET("v1/{range}")
    suspend fun getTimeSeries(
        @Path("range") range: String,
        @Query("base") base: String = "GBP",
        @Query("symbols") symbols: String = "USD"
    ): FrankfurterTimeSeriesResponse
}

object FrankfurterApi {
    val service: FrankfurterService by lazy {
        RetrofitClient.create(
            baseUrl = "https://api.frankfurter.dev/",
            converterFactory = GsonConverterFactory.create(),
            serviceClass = FrankfurterService::class.java
        )
    }
}
