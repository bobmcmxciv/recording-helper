package com.bob.recordinghelper.local

import android.content.Context
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.Prefs

/** First-run wizard state. Existing installations that are already configured never see the wizard. */
object Onboarding {
    private fun p(ctx: Context) = LocalSettings.prefs(ctx)
    fun done(ctx: Context): Boolean = p(ctx).getBoolean("onboardingDone", false)
    fun step(ctx: Context): Int = p(ctx).getInt("onboardingStep", 0)
    fun setStep(ctx: Context, step: Int) { p(ctx).edit().putInt("onboardingStep", step).apply() }
    fun finish(ctx: Context) { p(ctx).edit().putBoolean("onboardingDone", true).putInt("onboardingStep", 0).apply() }
    fun reopen(ctx: Context) { p(ctx).edit().putBoolean("onboardingDone", false).putInt("onboardingStep", 0).apply() }

    /** Show the wizard only on a fresh install: nothing configured, no model, no records. */
    fun shouldShow(ctx: Context): Boolean {
        if (done(ctx)) return false
        val configured = Prefs.scanScope(ctx) != null || LocalAsr.modelReady(ctx) || LocalStore.of(ctx).all("recording").isNotEmpty()
        if (configured) { finish(ctx); return false }
        return true
    }
}
