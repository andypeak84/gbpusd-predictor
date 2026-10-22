package com.example.gbpusdpredictor.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.gbpusdpredictor.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google's User Messaging Platform (UMP) consent flow, run before any ad is requested.
 *
 * Why it exists: users in the UK and EEA must be asked for consent before personalised-ad data is
 * used, and this app's audience is mainly UK. UMP shows the right form for the user's region (or none
 * outside the UK/EEA) and remembers the answer; `canRequestAds()` is the single gate for loading ads.
 * MobileAds is initialised only after that gate says yes, exactly once per process.
 *
 * Re-opening the form later (a legal requirement where it applies) is `showPrivacyOptions`, offered
 * from Diagnostics.
 */
object ConsentManager {
    private const val TAG = "ConsentManager"
    private val mobileAdsInitialised = AtomicBoolean(false)

    /** Runs [onReady] at most once, on the main thread, as soon as ads may be requested. */
    fun whenAdsAllowed(activity: Activity, onReady: () -> Unit) {
        val consentInfo = UserMessagingPlatform.getConsentInformation(activity)
        val delivered = AtomicBoolean(false)
        fun deliverIfAllowed() {
            if (consentInfo.canRequestAds() && delivered.compareAndSet(false, true)) {
                initialiseMobileAdsOnce(activity.applicationContext)
                onReady()
            }
        }

        // Ads may already be allowed from a previous session's consent: don't make the banner wait
        // for the network round trip below.
        deliverIfAllowed()

        consentInfo.requestConsentInfoUpdate(
            activity,
            requestParameters(activity),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) Log.w(TAG, "consent form: ${formError.errorCode} ${formError.message}")
                    deliverIfAllowed()
                }
            },
            { error ->
                Log.w(TAG, "consent info update failed: ${error.errorCode} ${error.message}")
                deliverIfAllowed()
            }
        )
    }

    /** True where the user must be able to change their consent choice later (UK/EEA). */
    fun privacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) Log.w(TAG, "privacy options form: ${formError.errorCode} ${formError.message}")
        }
    }

    private fun requestParameters(activity: Activity): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        // Debug builds only, and only when local.properties sets UMP_TEST_DEVICE_ID: pretend to be in
        // the EEA so the consent form can be seen and tested from anywhere.
        if (BuildConfig.DEBUG && BuildConfig.UMP_TEST_DEVICE_ID.isNotBlank()) {
            builder.setConsentDebugSettings(
                ConsentDebugSettings.Builder(activity)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .addTestDeviceHashedId(BuildConfig.UMP_TEST_DEVICE_ID)
                    .build()
            )
        }
        return builder.build()
    }

    private fun initialiseMobileAdsOnce(appContext: Context) {
        if (mobileAdsInitialised.compareAndSet(false, true)) MobileAds.initialize(appContext)
    }
}
