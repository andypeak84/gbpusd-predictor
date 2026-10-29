package com.example.gbpusdpredictor.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * Dark-mode helpers. The theme (Theme.AppCompat.DayNight) already recolours the default text and window; this covers the few
 * colours the screens set themselves, which would be unreadable on a dark background (dark blue text, a white chart box).
 */
object Night {
    fun isOn(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The blue used for "this includes news" text: dark blue on light, light blue on dark. */
    fun accentBlue(context: Context): Int = Color.parseColor(if (isOn(context)) "#7DB2FF" else "#1565C0")
}
