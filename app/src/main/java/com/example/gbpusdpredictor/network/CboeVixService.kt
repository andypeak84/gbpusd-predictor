package com.example.gbpusdpredictor.network

import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET

/**
 * Cboe's own daily VIX history (DATE,OPEN,HIGH,LOW,CLOSE since 1990, about 400 KB), fetched on the phone itself. FRED's copy of the
 * same series (via the Oilfetcher repo) runs one to three business days late, so the model often had no VIX for the day it needed.
 *
 * Personal use only: Cboe's website terms allow one personal, non-commercial download and forbid publishing or redistributing the
 * data without a licence. The app is no longer distributed (pulled from Play testing, 2026-11-25) and runs only on the owner's
 * phone, which reads the file directly; nothing is republished. Do not ship this to other users without a Cboe licence.
 */
interface CboeVixService {
    @GET("api/global/us_indices/daily_prices/VIX_History.csv")
    suspend fun getVixHistoryCsv(): String
}

object CboeVixApi {
    val service: CboeVixService by lazy {
        RetrofitClient.create(
            baseUrl = "https://cdn.cboe.com/",
            converterFactory = ScalarsConverterFactory.create(),
            serviceClass = CboeVixService::class.java
        )
    }
}
