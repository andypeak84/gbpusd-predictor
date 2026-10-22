package com.example.gbpusdpredictor.work

import android.content.Context

/**
 * Settings + state for the hourly price-alert feature (PriceAlertWorker/Scheduler),
 * separate from MarketDataWorker's own PREFS_NAME -- different cadence, different
 * purpose (a live threshold/move alarm, not the daily forecast), no reason to share a
 * file. lastHourlyRate/lastHourlyRateUtc are written by the worker itself after every
 * fetch (not just on an alert) so the % hourly-move check always compares against the
 * immediately-preceding reading, not an arbitrarily-older one.
 */
object PriceAlertPrefs {
    private const val PREFS_NAME = "price_alert_settings"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_UPPER_THRESHOLD = "upper_threshold"
    private const val KEY_LOWER_THRESHOLD = "lower_threshold"
    private const val KEY_HOURLY_MOVE_PCT = "hourly_move_pct"
    private const val KEY_LAST_HOURLY_RATE = "last_hourly_rate"
    private const val KEY_LAST_HOURLY_RATE_UTC = "last_hourly_rate_utc"
    private const val KEY_LAST_FETCH_ATTEMPT_UTC = "last_fetch_attempt_utc"
    private const val KEY_LAST_FETCH_OUTCOME = "last_fetch_outcome"

    private const val KEY_ALPHA_VANTAGE_KEY = "alpha_vantage_key"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // The user's own free Alpha Vantage key -- entered in settings, stored only on the device. It
    // used to be compiled into the APK, which is unsafe for a public release.
    var Context.alphaVantageKey: String
        get() = prefs(this).getString(KEY_ALPHA_VANTAGE_KEY, "") ?: ""
        set(value) = prefs(this).edit().putString(KEY_ALPHA_VANTAGE_KEY, value.trim()).apply()

    var Context.priceAlertsEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_ENABLED, false)
        set(value) = prefs(this).edit().putBoolean(KEY_ENABLED, value).apply()

    // Stored as String so "unset" is representable (a 0.0 default would be a valid,
    // confusing threshold of its own) -- null means "this trigger is off."
    var Context.upperThreshold: Double?
        get() = prefs(this).getString(KEY_UPPER_THRESHOLD, null)?.toDoubleOrNull()
        set(value) = prefs(this).edit().putString(KEY_UPPER_THRESHOLD, value?.toString()).apply()

    var Context.lowerThreshold: Double?
        get() = prefs(this).getString(KEY_LOWER_THRESHOLD, null)?.toDoubleOrNull()
        set(value) = prefs(this).edit().putString(KEY_LOWER_THRESHOLD, value?.toString()).apply()

    // Stored as a fraction (0.005 == 0.5%), same convention as the rest of the app's
    // *ChangePct fields.
    var Context.hourlyMovePct: Double?
        get() = prefs(this).getString(KEY_HOURLY_MOVE_PCT, null)?.toDoubleOrNull()
        set(value) = prefs(this).edit().putString(KEY_HOURLY_MOVE_PCT, value?.toString()).apply()

    var Context.lastHourlyRate: Double?
        get() = prefs(this).getString(KEY_LAST_HOURLY_RATE, null)?.toDoubleOrNull()
        set(value) = prefs(this).edit().putString(KEY_LAST_HOURLY_RATE, value?.toString()).apply()

    var Context.lastHourlyRateUtc: String?
        get() = prefs(this).getString(KEY_LAST_HOURLY_RATE_UTC, null)
        set(value) = prefs(this).edit().putString(KEY_LAST_HOURLY_RATE_UTC, value).apply()

    // Same heartbeat-trace reasoning as MarketDataWorker's KEY_LAST_ATTEMPT_UTC -- lets
    // the settings screen show "last checked" / "last error" without needing Logcat.
    var Context.lastFetchAttemptUtc: String?
        get() = prefs(this).getString(KEY_LAST_FETCH_ATTEMPT_UTC, null)
        set(value) = prefs(this).edit().putString(KEY_LAST_FETCH_ATTEMPT_UTC, value).apply()

    var Context.lastFetchOutcome: String?
        get() = prefs(this).getString(KEY_LAST_FETCH_OUTCOME, null)
        set(value) = prefs(this).edit().putString(KEY_LAST_FETCH_OUTCOME, value).apply()
}
