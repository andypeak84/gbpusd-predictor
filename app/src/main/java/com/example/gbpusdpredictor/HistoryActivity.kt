package com.example.gbpusdpredictor

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.gbpusdpredictor.data.AppDatabase
import com.example.gbpusdpredictor.data.MarketData
import com.example.gbpusdpredictor.utils.AppClock
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Shows every row in the market_data table, oldest-to-newest by trading date (newest at
 * top -- see getAllEntries's own ordering). Exists purely so it's possible to directly
 * verify the app is actually recording data day to day, rather than trusting the summary
 * numbers on the main screen. One row per trading date now (see MarketData.kt's v5->v6
 * note) -- refreshData() itself refuses to write a second row for a date that already has
 * one, so consecutive rows sharing a Date should no longer happen in normal operation.
 *
 * Also owns Export/Import: a signing-key change forces an uninstall (Android won't update
 * a differently-signed app in place), which wipes the local database -- not backed up
 * anywhere else. Export writes everything to a JSON file the user picks the location for
 * (Drive, Downloads, email attachment, wherever); Import reads one back in.
 */
class HistoryActivity : AppCompatActivity() {

    private val columnWidthDp = 100
    private val gson = Gson()
    // See MainActivity's identical comment -- includes the date, not just HH:mm, since
    // Date and Captured (UTC) can now legitimately differ by a day or more.
    private val capturedAtFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneOffset.UTC)

    private fun capturedAtLabel(row: MarketData): String = try {
        capturedAtFormatter.format(java.time.Instant.parse(row.capturedAtUtc))
    } catch (e: Exception) {
        "--"
    }

    // Backup file schema. Older backups may still contain manualUk2yValue/manualUk2yDate
    // fields (from before that feature was withdrawn now that a real live UK rate source
    // exists) -- Gson silently ignores unrecognized JSON fields on import, so those old
    // backups still import fine, just without restoring a field this app no longer has.
    private data class BackupPayload(
        val marketData: List<MarketData>
    )

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 8)
        }
        buttonRow.addView(Button(this).apply {
            text = "Export Data"
            setOnClickListener { exportLauncher.launch("gbpusd-predictor-backup-${AppClock.today()}.json") }
        })
        buttonRow.addView(Button(this).apply {
            text = "Import Data"
            setOnClickListener { importLauncher.launch(arrayOf("application/json")) }
        })
        buttonRow.addView(Button(this).apply {
            text = "Bootstrap Log"
            setOnClickListener { startActivity(android.content.Intent(this@HistoryActivity, BootstrapLogActivity::class.java)) }
        })
        root.addView(buttonRow)

        root.addView(TextView(this).apply {
            text = "\u201cDate\u201d is the trading day the row is FOR. \u201cCaptured (UTC)\u201d is when the refresh actually ran to fetch it -- normally the following day, since the refresh waits for that trading day to fully settle before fetching it."
            textSize = 11f
            setPadding(16, 0, 16, 12)
        })

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val horizontalScroll = HorizontalScrollView(this).apply { addView(rows) }
        root.addView(horizontalScroll)

        setContentView(ScrollView(this).apply { addView(root) })

        loadAndRenderTable()
    }

    private fun fmt(value: Double?, pattern: String): String =
        value?.let { pattern.format(Locale.UK, it) } ?: "—"

    private fun loadAndRenderTable() {
        rows.removeAllViews()
        addRow(rows, listOf("Loading..."), bold = false)

        CoroutineScope(Dispatchers.Main).launch {
            val all = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(applicationContext).marketDao().getAllEntries()
            }

            rows.removeAllViews()

            if (all.isEmpty()) {
                addRow(rows, listOf("No data recorded yet."), bold = false)
                return@launch
            }

            addRow(
                rows,
                listOf("Date", "Captured (UTC)", "GBP/USD", "5d chg", "Predicted", "Pred chg", "UK 2yr yield", "US 2y", "VIX", "Oil 5d chg", "RSI(14)", "GBP/EUR 5d", "EUR/USD 5d", "USD basket 5d", "Realised vol 10d"),
                bold = true
            )
            all.forEach { d ->
                addRow(
                    rows,
                    listOf(
                        d.date,
                        capturedAtLabel(d),
                        fmt(d.gbpUsd, "%.4f"),
                        d.gbpUsdChange5d?.let { "%+.2f%%".format(Locale.UK, it * 100) } ?: "—",
                        d.predicted14dRate?.let { "%.4f".format(Locale.UK, it) } ?: "—",
                        d.predicted14dChangePct?.let { "%+.2f%%".format(Locale.UK, it * 100) } ?: "—",
                        fmt(d.ukRate, "%.3f"),
                        fmt(d.us2y, "%.3f"),
                        fmt(d.vix, "%.2f"),
                        d.oilChange5d?.let { "%+.2f%%".format(Locale.UK, it * 100) } ?: "—",
                        fmt(d.rsi14, "%.1f"),
                        d.gbpEurChange5d?.let { "%+.2f%%".format(Locale.UK, it * 100) } ?: "—",
                        d.eurUsdChange5d?.let { "%+.2f%%".format(Locale.UK, it * 100) } ?: "—",
                        d.usdBasketChange5d?.let { "%+.2f%%".format(Locale.UK, it * 100) } ?: "—",
                        d.gbpUsdRealVol10d?.let { "%.4f".format(Locale.UK, it) } ?: "—"
                    ),
                    bold = false
                )
            }
        }
    }

    private fun exportTo(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    val all = AppDatabase.getInstance(applicationContext).marketDao().getAllEntries()
                    val payload = BackupPayload(marketData = all)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        OutputStreamWriter(out).use { it.write(gson.toJson(payload)) }
                    } ?: throw IllegalStateException("Couldn't open the chosen file for writing")
                }
                Toast.makeText(this@HistoryActivity, "Exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importFrom(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val restoredCount = withContext(Dispatchers.IO) {
                    val json = contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input)).readText()
                    } ?: throw IllegalStateException("Couldn't open the chosen file for reading")

                    // Backups from before v44 (v4->v5 schema change: `date` stopped being
                    // the primary key, `capturedAtUtc` was added) won't have a
                    // capturedAtUtc field at all -- Gson would leave that non-null column
                    // as null, which Room rejects at insert time (NOT NULL constraint).
                    // Patched in here as midnight UTC on that row's own date, ahead of the
                    // normal Gson binding below, same @Suppress("DEPRECATION") reasoning as
                    // MarketRepository's own JsonParser use (broadest Gson-version support).
                    @Suppress("DEPRECATION")
                    val root = com.google.gson.JsonParser().parse(json).asJsonObject
                    root.getAsJsonArray("marketData")?.forEach { el ->
                        val obj = el.asJsonObject
                        if (!obj.has("capturedAtUtc") || obj.get("capturedAtUtc").isJsonNull) {
                            val date = obj.get("date")?.takeIf { it.isJsonPrimitive }?.asString
                            obj.addProperty("capturedAtUtc", "${date ?: AppClock.today()}T00:00:00Z")
                        }
                    }

                    val payload = gson.fromJson(root, BackupPayload::class.java)
                        ?: throw IllegalStateException("File doesn't look like a valid backup")

                    AppDatabase.getInstance(applicationContext).marketDao().insertAll(payload.marketData)

                    payload.marketData.size
                }
                Toast.makeText(this@HistoryActivity, "Imported $restoredCount row(s)", Toast.LENGTH_SHORT).show()
                loadAndRenderTable()
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addRow(parent: LinearLayout, cells: List<String>, bold: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val widthPx = (columnWidthDp * resources.displayMetrics.density).toInt()
        cells.forEach { text ->
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                gravity = Gravity.START
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            })
        }
        parent.addView(row)
    }
}
