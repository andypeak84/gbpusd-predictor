package com.example.gbpusdpredictor.ads

import android.app.Activity
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.gbpusdpredictor.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * One anchored adaptive banner, loaded into [holder] once the consent flow (ConsentManager) allows ads.
 * The holder sits below the screen's ScrollView (see ui/ScreenHost), never over it, so the banner can
 * never cover a button or a slider (AdMob placement policy).
 *
 * The ad unit id is Google's TEST id in debug builds and the owner's real one in release (BuildConfig,
 * set in app/build.gradle).
 */
object AdBanner {
    fun attach(activity: AppCompatActivity, holder: FrameLayout) {
        ConsentManager.whenAdsAllowed(activity) {
            if (activity.isFinishing || activity.isDestroyed) return@whenAdsAllowed

            val density = activity.resources.displayMetrics.density
            val widthDp = (activity.resources.displayMetrics.widthPixels / density).toInt()
            val adView = AdView(activity).apply {
                adUnitId = BuildConfig.ADMOB_BANNER_ID
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp))
            }
            holder.addView(adView)
            bindToLifecycle(activity, adView)
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    private fun bindToLifecycle(activity: Activity, adView: AdView) {
        (activity as AppCompatActivity).lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = adView.resume()
            override fun onPause(owner: LifecycleOwner) = adView.pause()
            override fun onDestroy(owner: LifecycleOwner) = adView.destroy()
        })
    }
}
