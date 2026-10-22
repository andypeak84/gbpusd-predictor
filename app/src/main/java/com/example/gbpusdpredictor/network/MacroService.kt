package com.example.gbpusdpredictor.network

import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * The US 2-year Treasury yield and the VIX, published as plain JSON files by the data repo's Action
 * (andypeak84/Oilfetcher, scripts/fetch_macro.py: the yield from the U.S. Treasury, the VIX from FRED).
 * Same shape as the oil files: a JSON array of {"date": "2026-10-21", "price": 4.81}.
 *
 * This replaces the app calling FRED directly: that needed an API key compiled into the APK, which for
 * a public Google Play release anyone could extract. The key now lives only in a GitHub Actions secret.
 *
 * cacheBust: raw.githubusercontent.com's CDN ignores a client Cache-Control request header (see
 * OilPriceService's comment), so a fresh query value on every call is what forces a fresh copy.
 */
interface MacroService {
    @Headers("Cache-Control: no-cache")
    @GET("andypeak84/Oilfetcher/main/data/us2y.json")
    suspend fun getUs2yRaw(@Query("cacheBust") cacheBust: Long = System.currentTimeMillis()): String

    @Headers("Cache-Control: no-cache")
    @GET("andypeak84/Oilfetcher/main/data/vix.json")
    suspend fun getVixRaw(@Query("cacheBust") cacheBust: Long = System.currentTimeMillis()): String
}

object MacroApi {
    val service: MacroService by lazy {
        RetrofitClient.create(
            baseUrl = "https://raw.githubusercontent.com/",
            converterFactory = ScalarsConverterFactory.create(),
            serviceClass = MacroService::class.java
        )
    }
}
