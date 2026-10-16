package com.example.gbpusdpredictor.network

import com.google.gson.annotations.SerializedName
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Live (near-real-time, not daily-settled) GBP/USD rate for the hourly price-alert
 * feature -- deliberately separate from every other data source in this app.
 * FRED/Frankfurter/the GitHub-Action fetchers are all daily-granularity by design (see
 * MarketRepository's whole previousBusinessDay()-anchored refresh model) and wrong for
 * this: an alert that should fire within the hour can't wait for a once-daily refresh.
 *
 * CURRENCY_EXCHANGE_RATE is Alpha Vantage's lightweight "latest quote" endpoint (not
 * FX_INTRADAY's OHLC candles -- this feature only needs the single current rate, not a
 * series). Free tier is 25 requests/day, comfortably covers hourly polling (24/day)
 * with one call's headroom. "Last Refreshed" in the response is Alpha Vantage's own
 * upstream timestamp (not necessarily this exact second), consistent with this being a
 * near-real-time feed, not a guaranteed-fresh-to-the-second one.
 */
interface AlphaVantageService {
    @GET("query")
    suspend fun getExchangeRate(
        @Query("function") function: String = "CURRENCY_EXCHANGE_RATE",
        @Query("from_currency") fromCurrency: String = "GBP",
        @Query("to_currency") toCurrency: String = "USD",
        @Query("apikey") apiKey: String
    ): AlphaVantageExchangeRateResponse
}

data class AlphaVantageExchangeRateResponse(
    @SerializedName("Realtime Currency Exchange Rate")
    val rate: AlphaVantageRate?,
    // Alpha Vantage returns HTTP 200 with a body shaped like one of these three instead
    // of a 4xx/5xx for a bad key, a rate-limit hit, or a malformed request -- none of
    // which throws an HttpException, so callers must check for these explicitly rather
    // than relying on retryNetwork()'s IOException-based retry to ever catch them.
    @SerializedName("Error Message") val errorMessage: String? = null,
    @SerializedName("Note") val note: String? = null,
    @SerializedName("Information") val information: String? = null
)

data class AlphaVantageRate(
    @SerializedName("5. Exchange Rate") val exchangeRate: String?,
    @SerializedName("6. Last Refreshed") val lastRefreshed: String?
)

object AlphaVantageApi {
    val service: AlphaVantageService by lazy {
        RetrofitClient.create(
            baseUrl = "https://www.alphavantage.co/",
            converterFactory = GsonConverterFactory.create(),
            serviceClass = AlphaVantageService::class.java
        )
    }
}
