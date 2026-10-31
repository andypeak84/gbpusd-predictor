package com.example.gbpusdpredictor

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.gbpusdpredictor.ads.ConsentManager
import com.example.gbpusdpredictor.ui.ScreenHost

/**
 * The "behind the scenes" screen: the developer-style tools moved off the main screen for the public
 * release (forecast history with export/import, the data-fetch log), the ad-consent choices, and the
 * data credits and disclaimer that the sources' terms and Google Play expect to see.
 */
class DiagnosticsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        root.addView(TextView(this).apply {
            text = "Diagnostics"
            textSize = 22f
            setPadding(0, 0, 0, 16)
        })

        root.addView(button("Forecast history (export / import)") {
            startActivity(Intent(this, HistoryActivity::class.java))
        })
        root.addView(button("Data fetch log") {
            startActivity(Intent(this, BootstrapLogActivity::class.java))
        })
        // Google Play expects the app itself, not just the store listing, to point at the privacy policy.
        root.addView(button("Privacy policy") { openPrivacyPolicy() })
        if (ConsentManager.privacyOptionsRequired(this)) {
            root.addView(button("Privacy & ad choices") { ConsentManager.showPrivacyOptions(this) })
        }

        root.addView(TextView(this).apply {
            text = "Moving from an older install? Open Forecast history there, tap Export, then Import here."
            textSize = 12f
            setPadding(0, 8, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = CREDITS
            textSize = 13f
        })

        ScreenHost.install(this, ScrollView(this).apply { addView(root) })
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = label
        setOnClickListener { onClick() }
    }

    private fun openPrivacyPolicy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_POLICY_URL)))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No browser found -- visit $PRIVACY_POLICY_URL", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val PRIVACY_POLICY_URL = "https://andypeak84.github.io/gbpusd-predictor/privacy-policy.html"

        const val CREDITS = """Data sources

• Exchange rates (GBP/USD and the other currency pairs): European Central Bank reference rates, via Frankfurter (frankfurter.dev).
• Live GBP/USD quote (shown as "Live rate"): Alpha Vantage, using your own free key. Without a key the app shows the daily ECB rate above, labelled as such; the forecast and its scoring always use that daily rate.
• US 2-year Treasury yield: U.S. Department of the Treasury, daily par yield curve.
• VIX and other US market series: this product uses the FRED® API but is not endorsed or certified by the Federal Reserve Bank of St. Louis.
• Crude oil (Brent): OilPriceAPI (https://www.oilpriceapi.com/).
• UK Bank Rate and gilt yields: Bank of England, published under the Open Government Licence.
• News sentiment: headlines from the Bank of England, the Federal Reserve and other public news feeds, scored by the app's author using hand-written rules. Headline text and links belong to their publishers and are shown with a link to the original.

Important

Informational only. Not financial advice. The forecasts are statistical estimates and are often wrong; past accuracy does not predict future results. Do not make financial decisions based on this app."""
    }
}
