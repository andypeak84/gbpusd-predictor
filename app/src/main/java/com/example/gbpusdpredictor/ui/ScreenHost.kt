package com.example.gbpusdpredictor.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.gbpusdpredictor.ads.AdBanner

/**
 * Every screen sets its content through this instead of setContentView, which gives all of them:
 *
 *  1. Edge-to-edge insets. At targetSdk 35+ Android draws the app under the status and navigation bars;
 *     without padding the top/bottom of each screen would sit beneath them. The padding goes on the
 *     outer column, so the bottom navigation inset sits below the ad banner and above nothing.
 *  2. The AdMob banner pinned at the bottom, OUTSIDE the scrolling content so it never overlaps a
 *     control (see ads/AdBanner).
 */
object ScreenHost {
    fun install(activity: AppCompatActivity, content: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        // Edge-to-edge draws the app behind the system bars; the app has a light background, so the
        // status/navigation bar icons must be dark or the clock and battery vanish (white on light); in dark mode they are light.
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !Night.isOn(activity)
            isAppearanceLightNavigationBars = !Night.isOn(activity)
        }

        val adHolder = FrameLayout(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(adHolder, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(column) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        activity.setContentView(column)
        AdBanner.attach(activity, adHolder)
    }
}
