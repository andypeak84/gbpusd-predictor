package com.example.gbpusdpredictor.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Keyless fallback for a live GBP/USD quote: Kraken's public ticker (no account, no key). It is an exchange's own GBP/USD market,
 * not an interbank rate, so it can differ slightly from Alpha Vantage; the app labels every live reading with its source.
 * Used only when the user has no Alpha Vantage key or that key's daily budget is spent (see work/LiveRate).
 */
interface KrakenService {
    @GET("0/public/Ticker")
    suspend fun ticker(@Query("pair") pair: String = "GBPUSD"): String
}

object KrakenApi {
    val service: KrakenService by lazy {
        RetrofitClient.create(
            baseUrl = "https://api.kraken.com/",
            converterFactory = ScalarsConverterFactory.create(),
            serviceClass = KrakenService::class.java
        )
    }
}

/** Pure parsing, so it can be unit-tested: {"error":[],"result":{"ZGBPZUSD":{"a":[ask,...],"b":[bid,...],...}}}. */
object KrakenParser {
    private const val MIN_PLAUSIBLE = 0.5
    private const val MAX_PLAUSIBLE = 3.0

    /** Mid of the best bid and ask, or null for an error reply, a malformed body or an implausible number. */
    fun midRate(json: String): Double? = runCatching {
        val root = Gson().fromJson(json, JsonObject::class.java)
        if (root.getAsJsonArray("error")?.size() ?: 0 > 0) return null
        val pair = root.getAsJsonObject("result").entrySet().first().value.asJsonObject
        val ask = pair.getAsJsonArray("a")[0].asString.toDouble()
        val bid = pair.getAsJsonArray("b")[0].asString.toDouble()
        val mid = (ask + bid) / 2.0
        mid.takeIf { bid > 0 && ask >= bid && it in MIN_PLAUSIBLE..MAX_PLAUSIBLE }
    }.getOrNull()
}
