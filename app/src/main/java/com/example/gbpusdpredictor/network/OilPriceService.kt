package com.example.gbpusdpredictor.network

import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers

/**
 * The user's own WTI crude oil price history at
 * github.com/andypeak84/Oilfetcher/data/oil-price.json, written daily by the
 * oil-fred-fetcher GitHub Action (60-day rolling window, overwritten in full each run).
 *
 * Second attempt at this -- an earlier API-Ninjas-backed version was abandoned after
 * their free tier turned out premium-gated for crude oil on both endpoints it tried.
 * This one is backed by FRED's DCOILWTICO instead (the same source the app already
 * calls directly for us2y/vix), which has a genuine historical archive -- so unlike the
 * abandoned version, the Action re-fetches a fresh window every run rather than slowly
 * accumulating day by day, and a missed run self-heals on the next successful one
 * instead of leaving a permanent gap.
 *
 * Shape is a JSON array of {"date": "2026-08-20", "price": 67.82} objects (see
 * fetch_oil_price_fred.py). Fetched as raw text (not a typed Gson response), same
 * reasoning as GiltYieldService: MarketRepository.fetchOilHistoryFromGitHub tolerates
 * either a bare object or an array.
 */
interface OilPriceService {
    // Relative to RetrofitClient's baseUrl below -- must include the branch segment
    // (main), same shape as GiltYieldService's path. An earlier version dropped the
    // branch ("andypeak84/Oilfetcher/data/oil-price.json"), which raw.githubusercontent
    // answers with a 404 -- and a 404 is an HttpException, not an IOException, so
    // retryNetwork() didn't retry it and oilChange5d silently stayed null on every row.
    // raw.githubusercontent.com serves plain file bytes with no caching headers of its
    // own to worry about; Cache-Control: no-cache here just makes sure any intermediate
    // proxy doesn't hand back yesterday's snapshot instead of a fresh fetch.
    @Headers("Cache-Control: no-cache")
    @GET("andypeak84/Oilfetcher/main/data/oil-price.json")
    suspend fun getLatestRaw(): String
}

object OilPriceApi {
    val service: OilPriceService by lazy {
        RetrofitClient.create(
            baseUrl = "https://raw.githubusercontent.com/",
            converterFactory = ScalarsConverterFactory.create(),
            serviceClass = OilPriceService::class.java
        )
    }
}
