package com.example.gbpusdpredictor.network

import com.example.gbpusdpredictor.network.RetrofitClient
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface BoeApiService {
    @GET("_iadb-fromshowcolumns.asp")
    suspend fun getSeriesCsv(
        @Query("csv.x") csvFlag: String = "yes",
        @Query("Datefrom") dateFrom: String,
        @Query("Dateto") dateTo: String,
        @Query("SeriesCodes") seriesCodes: String,
        @Query("CSVF") csvFormat: String = "TN", 
        @Query("UsingCodes") usingCodes: String = "Y",
        @Query("VPD") includeProvisional: String = "Y",
        @Query("VFD") includeFootnotes: String = "N"
    ): String
}

object BoeApi {
    val service: BoeApiService by lazy {
        RetrofitClient.create(
            baseUrl = "https://www.bankofengland.co.uk/boeapps/database/",
            converterFactory = ScalarsConverterFactory.create(),
            serviceClass = BoeApiService::class.java
        )
    }
}
