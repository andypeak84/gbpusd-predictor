package com.example.gbpusdpredictor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.gbpusdpredictor.data.AppDatabase
import com.example.gbpusdpredictor.data.BootstrapFetchLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every bootstrap-style history fetch (Frankfurter's price-history pull, each FRED
 * series' history pull, the GitHub gilt-yield snapshot) now writes a row to
 * bootstrap_fetch_log instead of running silently in the background -- per explicit
 * request, this is where that gets "published properly" rather than left to Logcat. Read
 * this to answer, for any given day: did a source actually have a reading for the date we
 * needed (matchedTargetDate = OK), was it just behind (NO_MATCH -- newestObservationDate
 * shows how far behind), or did the fetch itself fail (EMPTY / ERROR)? Oil in particular
 * -- flagged as looking "stuck" for several days -- is directly diagnosable from the
 * fred_DCOILWTICO_history rows here: if newestObservationDate keeps trailing targetDate,
 * FRED genuinely hasn't published a new WTI settlement, not an app-side bug.
 */
class BootstrapLogActivity : AppCompatActivity() {

    private val columnWidthDp = 130

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = "Bootstrap fetch log"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(24, 24, 24, 4)
        })
        root.addView(TextView(this).apply {
            text = "Every history-style fetch a refresh made, and whether it actually found a reading for the exact date it needed."
            textSize = 12f
            setPadding(24, 0, 24, 16)
        })

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val horizontalScroll = HorizontalScrollView(this).apply { addView(rows) }
        root.addView(horizontalScroll)

        setContentView(ScrollView(this).apply { addView(root) })

        CoroutineScope(Dispatchers.Main).launch {
            val entries = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(applicationContext).bootstrapLogDao().getRecent(500)
            }

            if (entries.isEmpty()) {
                addRow(rows, listOf("No bootstrap fetches logged yet -- wait for the next 21:00 UTC refresh."), bold = false, color = null)
                return@launch
            }

            addRow(
                rows,
                listOf("Logged (UTC)", "Source", "Purpose", "Target date", "Newest obs. date", "Newest value", "# obs.", "Outcome"),
                bold = true,
                color = null
            )
            entries.forEach { e -> addRow(rows, rowCells(e), bold = false, color = outcomeColor(e)) }
        }
    }

    private fun rowCells(e: BootstrapFetchLog): List<String> = listOf(
        e.loggedAtUtc.take(16).replace("T", " "),
        e.source,
        e.purpose,
        e.targetDate ?: "—",
        e.newestObservationDate ?: "—",
        e.newestValue?.let { "%.4f".format(it) } ?: "—",
        e.observationsReturned.toString(),
        e.outcome
    )

    // Colour-codes the row so a stretch of NO_MATCH/EMPTY/ERROR is visible at a glance
    // when scanning a long log, rather than needing to read every "Outcome" cell.
    private fun outcomeColor(e: BootstrapFetchLog): Int? = when {
        e.outcome == "OK" -> null
        e.outcome.startsWith("ERROR") -> Color.parseColor("#E5484D")
        e.outcome == "EMPTY" -> Color.parseColor("#E5484D")
        e.outcome == "NO_MATCH" -> Color.parseColor("#F5A623")
        else -> null
    }

    private fun addRow(parent: LinearLayout, cells: List<String>, bold: Boolean, color: Int?) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val widthPx = (columnWidthDp * resources.displayMetrics.density).toInt()
        cells.forEach { text ->
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 12f
                gravity = Gravity.START
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                if (color != null) setTextColor(color)
            })
        }
        parent.addView(row)
    }
}
