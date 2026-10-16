package com.example.gbpusdpredictor.network

import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * The user's own Brent crude oil price history at
 * github.com/andypeak84/Oilfetcher/data/brent-price.json, written by the same
 * oil-fred-fetcher GitHub Action as WTI (see OilPriceService.kt), just a second
 * benchmark it now also fetches (FRED's DCOILBRENTEU, EIA's RBRTE as a fallback).
 *
 * Added alongside WTI, not instead of it: Brent is the global benchmark UK/European
 * fuel pricing actually tracks more closely, and a real comparison (2026-09) showed
 * Brent running $15-25/barrel above WTI while FRED's Brent series stayed current
 * through a stretch where WTI's stalled for over a week -- a second, independent
 * source of oil-market information, not a replacement for WTI's.
 *
 * Shape is identical to oil-price.json -- a JSON array of {"date", "price"} objects
 * (see fetch_oil_price_fred.py). Fetched as raw text for the same reason
 * OilPriceService/GiltYieldService are: MarketRepository.fetchBrentHistoryFromGitHub
 * tolerates either a bare object or an array.
 */
interface BrentPriceService {
    // Relative to RetrofitClient's baseUrl below -- must include the branch segment
    // (main), same shape as OilPriceService's/GiltYieldService's paths -- dropping it
    // is exactly the bug that made oilChange5d silently null on every row since v62
    // (see OilPriceService.kt's own comment); not repeating that here.
    // See OilPriceService.kt's own comment -- same raw.githubusercontent.com Fastly CDN,
    // same real staleness confirmed 2026-10 (this file was fetched a few seconds after
    // OilPriceService's on every refresh, and showed the identical 5-day-stale pattern
    // at the identical dates). Same cacheBust fix.
    @Headers("Cache-Control: no-cache")
    @GET("andypeak84/Oilfetcher/main/data/brent-price.json")
    suspend fun getLatestRaw(@Query("cacheBust") cacheBust: Long = System.currentTimeMillis()): String
}

object BrentPriceApi {
    val service: BrentPriceService by lazy {
        RetrofitClient.create(
            baseUrl = "https://raw.githubusercontent.com/",
            converterFactory = ScalarsConverterFactory.create(),
            serviceClass = BrentPriceService::class.java
        )
    }
}
