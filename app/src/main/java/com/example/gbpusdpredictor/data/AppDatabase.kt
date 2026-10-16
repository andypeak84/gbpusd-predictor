package com.example.gbpusdpredictor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MarketData::class, BootstrapFetchLog::class], version = 11, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun marketDao(): MarketDao
    abstract fun bootstrapLogDao(): BootstrapLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v9 -> v10's only schema change is one new nullable column -- a trivial, safe
        // ADD COLUMN, unlike every prior bump (all of which genuinely restructured
        // things: renamed columns, changed the primary key, added whole tables). Real
        // migration instead of fallbackToDestructiveMigration() here specifically
        // because the user's accuracy history (predicted-vs-actual, scored over weeks)
        // is something they explicitly asked not to lose -- wiping it to add one column
        // would be needlessly destructive when SQLite can do this losslessly in one
        // statement. Every EARLIER bump (v1->v9) still relies on destructive migration
        // below as a catch-all, since those genuinely can't be expressed as a simple
        // ALTER TABLE and this project's data has always been treated as a rebuildable
        // rolling cache until now.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE market_data ADD COLUMN brentChange5d REAL")
            }
        }

        // v10 -> v11: same reasoning as MIGRATION_9_10 -- three more simple ADD COLUMNs,
        // real migration to keep preserving the user's accuracy history rather than
        // falling back to a destructive wipe.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE market_data ADD COLUMN gbpUsdChange10d REAL")
                db.execSQL("ALTER TABLE market_data ADD COLUMN gbpUsdChange20d REAL")
                db.execSQL("ALTER TABLE market_data ADD COLUMN spread2yChange10d REAL")
            }
        }

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
                    // vol, computed on-device). v8 -> v9 added us2yChange5d / vixChange5d /
                    // gbpUsdChange1d / usdBasketChange1d (zero-new-fetch momentum
                    // companions) and dayOfWeek / monthOfYear / isMonthEnd (pure calendar).
                    // v9 -> v10 added brentChange5d (Brent crude, a second independent oil
                    // benchmark alongside WTI's oilChange5d). v10 -> v11 added
                    // gbpUsdChange10d / gbpUsdChange20d / spread2yChange10d (longer-trend
                    // momentum companions).
                    // All are a rolling daily cache rebuilt by the worker within a couple
                    // of days, so destructive migration is fine here.
                    // Also handles downgrades (not just undefined upgrade paths) -- this
                    // version number has been juggled a lot during development; cheap
                    // insurance against ever shipping a lower number than what's on-device.
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
