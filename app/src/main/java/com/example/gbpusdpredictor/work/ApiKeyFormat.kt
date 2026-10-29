package com.example.gbpusdpredictor.work

/**
 * Shape of the user's Alpha Vantage key, kept apart from the settings screen so it can be unit-tested.
 * Copied keys often carry a trailing space, a newline or quotes; the keys themselves are letters and digits only.
 */
object ApiKeyFormat {
    private val PLAUSIBLE = Regex("[A-Za-z0-9]{8,40}")

    fun clean(raw: String): String = raw.trim().trim('"', '\'', '`').trim()

    fun looksValid(key: String): Boolean = key.matches(PLAUSIBLE)
}
