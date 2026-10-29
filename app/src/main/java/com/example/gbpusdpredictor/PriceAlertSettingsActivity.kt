package com.example.gbpusdpredictor

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.gbpusdpredictor.work.PriceAlertPrefs
import com.example.gbpusdpredictor.work.PriceAlertScheduler
import com.example.gbpusdpredictor.work.PriceAlertWorker
import com.example.gbpusdpredictor.work.fetchLiveRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings for the hourly live price-alert feature (see PriceAlertWorker's own doc
 * comment for the full design). Two independent trigger kinds, both optional:
 *  - Absolute level: upper/lower rate thresholds.
 *  - Hourly % move: alert if the rate moves more than X% since the last hourly check.
 *
 * "Send Test Alert" fetches the live rate right now (bypassing the hourly chain and
 * any threshold check entirely) and force-posts a notification -- lets the user verify
 * the whole pipeline (API key, network, POST_NOTIFICATIONS permission) works without
 * waiting up to an hour or needing a real threshold breach.
 */
class PriceAlertSettingsActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var enabledCheckbox: CheckBox
    private lateinit var upperInput: EditText
    private lateinit var lowerInput: EditText
    private lateinit var movePctInput: EditText
    private lateinit var statusText: TextView
    private lateinit var liveText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Price Alerts"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "Checks the live GBP/USD rate roughly every hour (Alpha Vantage), independent of the daily forecast. Set any combination of the triggers below -- leave a field blank to turn that trigger off."
            textSize = 13f
            setPadding(0, 0, 0, 24)
        })

        root.addView(TextView(this).apply { text = "Your Alpha Vantage API key (free):" })
        keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "paste your key here"
        }
        root.addView(keyInput)
        // Getting a key is the only fiddly step: a form on Alpha Vantage's site (email only) that shows the key at once.
        // The app can't sign up for the user, so it opens the page and then pastes the copied key in one tap.
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyRow.addView(Button(this).apply {
            text = "Get a free key"
            setOnClickListener { openKeyPage() }
        })
        keyRow.addView(Button(this).apply {
            text = "Paste key"
            setOnClickListener { pasteKey() }
        })
        keyRow.addView(Button(this).apply {
            text = "Change key"
            setOnClickListener { unlockKey() }
        })
        root.addView(keyRow)
        liveText = TextView(this).apply { textSize = 16f; setPadding(0, 8, 0, 8) }
        root.addView(liveText)
        root.addView(TextView(this).apply {
            text = "1. Tap Get a free key, enter your email and press GET FREE API KEY.\n" +
                "2. Copy the key it shows, come back and tap Paste key. It is saved, checked and locked straight away, with the live rate shown above.\n" +
                "The key stays on this device and is only sent to Alpha Vantage. Alerts need a key; the daily forecast does not."
            textSize = 12f
            setPadding(0, 4, 0, 16)
        })

        enabledCheckbox = CheckBox(this).apply { text = "Enable price alerts" }
        root.addView(enabledCheckbox)

        root.addView(TextView(this).apply { text = "Alert if GBP/USD rises above:"; setPadding(0, 24, 0, 0) })
        upperInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "e.g. 1.3500"
        }
        root.addView(upperInput)

        root.addView(TextView(this).apply { text = "Alert if GBP/USD falls below:"; setPadding(0, 16, 0, 0) })
        lowerInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "e.g. 1.3000"
        }
        root.addView(lowerInput)

        root.addView(TextView(this).apply { text = "Alert if it moves more than this % within an hour:"; setPadding(0, 16, 0, 0) })
        movePctInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "e.g. 0.5"
        }
        root.addView(movePctInput)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }
        buttonRow.addView(Button(this).apply {
            text = "Save"
            setOnClickListener { saveSettings() }
        })
        buttonRow.addView(Button(this).apply {
            text = "Send Test Alert"
            setOnClickListener { sendTestAlert() }
        })
        root.addView(buttonRow)

        statusText = TextView(this).apply { setPadding(0, 24, 0, 0); textSize = 12f }
        root.addView(statusText)

        com.example.gbpusdpredictor.ui.ScreenHost.install(this, ScrollView(this).apply { addView(root) })

        loadSettings()
        refreshStatus()
        val savedKey = with(PriceAlertPrefs) { alphaVantageKey }
        if (savedKey.isNotEmpty()) checkKey(savedKey)
    }

    private fun loadSettings() {
        keyInput.setText(with(PriceAlertPrefs) { alphaVantageKey })
        enabledCheckbox.isChecked = priceAlertsEnabledValue()
        upperInput.setText(upperThresholdValue()?.let { "%.4f".format(Locale.UK, it) } ?: "")
        lowerInput.setText(lowerThresholdValue()?.let { "%.4f".format(Locale.UK, it) } ?: "")
        movePctInput.setText(hourlyMovePctValue()?.let { "%.2f".format(Locale.UK, it * 100) } ?: "")
    }

    // Thin wrappers around PriceAlertPrefs's Context extension properties -- an
    // Activity IS a Context, but Kotlin extension properties declared inside an object
    // (PriceAlertPrefs) need that object as the dispatch receiver too, hence `with`.
    private fun priceAlertsEnabledValue() = with(PriceAlertPrefs) { priceAlertsEnabled }
    private fun upperThresholdValue() = with(PriceAlertPrefs) { upperThreshold }
    private fun lowerThresholdValue() = with(PriceAlertPrefs) { lowerThreshold }
    private fun hourlyMovePctValue() = with(PriceAlertPrefs) { hourlyMovePct }

    private fun saveSettings() {
        val upper = upperInput.text.toString().trim().toDoubleOrNull()
        val lower = lowerInput.text.toString().trim().toDoubleOrNull()
        val movePct = movePctInput.text.toString().trim().toDoubleOrNull()?.div(100.0)
        val key = cleanKey(keyInput.text.toString())
        keyInput.setText(key)
        val enabled = enabledCheckbox.isChecked && key.isNotEmpty()
        if (enabledCheckbox.isChecked && key.isEmpty()) {
            enabledCheckbox.isChecked = false
            Toast.makeText(this, "Enter your Alpha Vantage API key to enable alerts", Toast.LENGTH_LONG).show()
        }

        with(PriceAlertPrefs) {
            alphaVantageKey = key
            priceAlertsEnabled = enabled
            upperThreshold = upper
            lowerThreshold = lower
            hourlyMovePct = movePct
        }

        if (enabled) {
            PriceAlertScheduler.start(this)
        } else {
            PriceAlertScheduler.stop(this)
        }

        Toast.makeText(this, if (enabled) "Price alerts enabled" else "Price alerts disabled", Toast.LENGTH_SHORT).show()
        refreshStatus()
        if (key.isNotEmpty()) checkKey(key)
    }

    private fun openKeyPage() {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.alphavantage.co/support/#api-key")))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No browser found -- visit alphavantage.co/support/#api-key", Toast.LENGTH_LONG).show()
        }
    }

    /** Copied keys often carry a trailing space, a newline or quotes; Alpha Vantage keys are letters and digits only. */
    private fun cleanKey(raw: String) = raw.trim().trim('"', '\'', '`').trim()

    private fun pasteKey() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val key = text?.let { cleanKey(it) }.orEmpty()
        when {
            key.isEmpty() -> Toast.makeText(this, "Nothing copied yet -- copy the key on the Alpha Vantage page first", Toast.LENGTH_LONG).show()
            !key.matches(Regex("[A-Za-z0-9]{8,40}")) ->
                Toast.makeText(this, "The copied text doesn't look like a key (letters and digits only)", Toast.LENGTH_LONG).show()
            else -> {
                keyInput.setText(key)
                with(PriceAlertPrefs) { alphaVantageKey = key }
                checkKey(key)
            }
        }
    }

    /**
     * One live request (of the free 25/day) so a mistyped or unactivated key shows up now, not at the next hourly check.
     * A key that works is locked (field read-only) and its rate shown and recorded as the baseline for the hourly-move
     * trigger; "Change key" unlocks it.
     */
    private fun checkKey(key: String) {
        CoroutineScope(Dispatchers.Main).launch {
            liveText.text = "Checking your key..."
            val result = withContext(Dispatchers.IO) { runCatching { fetchLiveRate(key) } }
            result.onSuccess { rate ->
                with(PriceAlertPrefs) {
                    lastHourlyRate = rate
                    lastHourlyRateUtc = java.time.Instant.now().toString()
                    lastFetchAttemptUtc = java.time.Instant.now().toString()
                    lastFetchOutcome = "OK"
                }
                keyInput.isEnabled = false
                liveText.text = "Key locked. Live GBP/USD: %.4f (%s UTC)".format(
                    Locale.UK, rate, java.time.LocalTime.now(java.time.ZoneOffset.UTC).toString().take(5))
                refreshStatus()
            }.onFailure { e ->
                keyInput.isEnabled = true
                liveText.text = "Key check failed: ${e.message}"
            }
        }
    }

    private fun unlockKey() {
        keyInput.isEnabled = true
        keyInput.requestFocus()
        liveText.text = "Key unlocked -- paste or type a new one."
    }

    private fun sendTestAlert() {
        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "Fetching live rate..."
            val result = withContext(Dispatchers.IO) { runCatching { fetchLiveRate(keyInput.text.toString().trim()) } }
            result.onSuccess { rate ->
                postTestNotification(rate)
                statusText.text = "Test alert sent -- live rate was %.4f.".format(Locale.UK, rate)
            }.onFailure { e ->
                statusText.text = "Test alert failed: ${e.message}"
                Toast.makeText(this@PriceAlertSettingsActivity, "Fetch failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun postTestNotification(rate: Double) {
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, "Notifications are disabled for this app -- enable them in system settings to receive alerts.", Toast.LENGTH_LONG).show()
            return
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, PriceAlertWorker.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GBP/USD price alert (test)")
            .setContentText("Test alert -- current live rate is %.4f. If you see this, alerts are working.".format(Locale.UK, rate))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        androidx.core.app.NotificationManagerCompat.from(this).notify(9999, notification)
    }

    private fun refreshStatus() {
        with(PriceAlertPrefs) {
            val lastAttempt = lastFetchAttemptUtc
            val lastOutcome = lastFetchOutcome
            val lastRate = lastHourlyRate
            statusText.text = buildString {
                append("Last hourly check: ${lastAttempt ?: "never"}\n")
                append("Last outcome: ${lastOutcome ?: "n/a"}\n")
                append("Last recorded rate: ${lastRate?.let { "%.4f".format(Locale.UK, it) } ?: "n/a"}")
            }
        }
    }
}
