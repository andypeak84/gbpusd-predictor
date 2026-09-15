package com.example.gbpusdpredictor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MarketData::class, BootstrapFetchLog::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun marketDao(): MarketDao
    abstract fun bootstrapLogDao(): BootstrapLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "market_database"
                )
                    // v1 -> v2 added predicted14dRate/predicted14dChangePct.
                    // v2 -> v3 renamed uk2y -> ukRate. v3 -> v4 added carriedForwardFields.
                    // v4 -> v5 replaced the `date`-String primary key with an
                    // auto-generated `id`. v5 -> v6: every market field became nullable
                    // (Double?) and carriedForwardFields was removed -- see MarketData.kt's
                    // v5->v6 note -- plus a new bootstrap_fetch_log table. v6 -> v7 added
                    // gbpEurChange5d / eurUsdChange5d / usdBasketChange5d (FX-cross
                    // momentum from Frankfurter). v7 -> v8 added gbpUsdRealVol10d (realized
                    // vol, computed on-device). All are a rolling daily cache rebuilt by
                    // the worker within a couple of days, so destructive migration is fine
                    // here.
                    // Also handles downgrades (not just undefined upgrade paths) -- this
                    // version number has been juggled a lot during development; cheap
                    // insurance against ever shipping a lower number than what's on-device.
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
