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

    private lateinit var enabledCheckbox: CheckBox
    private lateinit var upperInput: EditText
    private lateinit var lowerInput: EditText
    private lateinit var movePctInput: EditText
    private lateinit var statusText: TextView

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

        setContentView(ScrollView(this).apply { addView(root) })

        loadSettings()
        refreshStatus()
    }

    private fun loadSettings() {
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
        val enabled = enabledCheckbox.isChecked

        with(PriceAlertPrefs) {
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
    }

    private fun sendTestAlert() {
        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "Fetching live rate..."
            val result = withContext(Dispatchers.IO) { runCatching { fetchLiveRate() } }
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
