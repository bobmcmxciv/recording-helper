package com.bob.recordinghelper.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.widget.EditText
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import com.bob.recordinghelper.Prefs
import org.junit.Assert.*
import org.junit.Test

/** Isolated preferences + callback-only UI: never changes the owner's live range or imports audio. */
class ImportChoiceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun isolated(block: (Context) -> Unit) {
        val name = "choice-test-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(ins.targetContext) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(name, mode)
        }
        try { block(context) } finally { ins.targetContext.deleteSharedPreferences(name) }
    }

    @Test fun freshInstallAndOptOutCannotResumeAnUnchosenRange() = isolated { ctx ->
        assertNull(Prefs.scanScope(ctx)); assertFalse(Prefs.autoScan(ctx))
        assertEquals(0, Prefs.recentScanDays(ctx))
        assertEquals("尚未选择处理范围", AutoImportSettings.scopeLabel(ctx))
        try { Prefs.pauseAutoScan(ctx, false); fail("Implicit enable was accepted") } catch (_: IllegalArgumentException) {}
        Prefs.save(ctx, "", "", false, "", "0")
        assertNull("First-launch migration must not imply all history", Prefs.scanScope(ctx))
        Prefs.disableAutoScan(ctx)
        assertEquals("off", Prefs.scanScope(ctx)); assertFalse(Prefs.autoScan(ctx))
        try { Prefs.pauseAutoScan(ctx, false); fail("Opt-out was overridden") } catch (_: IllegalArgumentException) {}
    }

    @Test fun rememberedRangeAndPauseKeepTheOriginalNewCutoff() = isolated { ctx ->
        Prefs.setScanScope(ctx, "recent", 14)
        assertTrue(Prefs.autoScan(ctx)); assertEquals("recent", Prefs.scanScope(ctx))
        assertEquals(14, Prefs.scanDays(ctx)); assertEquals(0L, Prefs.scanSince(ctx))
        Prefs.setScanScope(ctx, "new")
        val since = Prefs.scanSince(ctx)
        Prefs.pauseAutoScan(ctx, true)
        Thread.sleep(5)
        Prefs.setScanScope(ctx, "new") // Saving the unchanged selection also means resume.
        assertEquals(since, Prefs.scanSince(ctx))
        Prefs.pauseAutoScan(ctx, true); Prefs.pauseAutoScan(ctx, false)
        assertEquals(since, Prefs.scanSince(ctx)); assertEquals(14, Prefs.recentScanDays(ctx))
        Prefs.setScanScope(ctx, "all")
        assertEquals(0L, Prefs.scanSince(ctx)); assertEquals(0, Prefs.scanDays(ctx))
        Prefs.disableAutoScan(ctx)
        assertEquals("off", Prefs.scanScope(ctx)); assertEquals(14, Prefs.recentScanDays(ctx))
        val before = ctx.getSharedPreferences("rh_prefs", 0).all
        try { Prefs.setScanScope(ctx, "recent", 0); fail("Invalid days accepted") } catch (_: IllegalArgumentException) {}
        assertEquals(before, ctx.getSharedPreferences("rh_prefs", 0).all)
    }

    @Test fun beta2UpgradeRetainsActiveAndPausedRanges() = isolated { ctx ->
        val p = ctx.getSharedPreferences("rh_prefs", 0)
        for (enabled in listOf(true, false)) {
            for ((mode, since, days) in listOf(Triple("new", 123L, 0), Triple("recent", 0L, 60), Triple("all", 0L, 0))) {
                p.edit().clear().putBoolean("autoScan", enabled).putLong("scanSince", since).putString("scanDays", days.toString()).commit()
                val before = p.all
                assertEquals(mode, Prefs.scanScope(ctx)); assertEquals(enabled, Prefs.autoScan(ctx))
                assertEquals(before, p.all) // Reading/migrating must not silently rewrite settings.
            }
        }
    }

    @Test fun nativeDialogRequiresChoiceAndNeverFallsBackToSixtyDays() {
        val ctx = ins.targetContext
        val before = AutoImportSettings.status(ctx).toString()
        org.junit.Assume.assumeFalse("Unlock the device before visible UI verification",
            ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
        // Some OEM systems abort an instrumented background launch of an internal activity.
        // Use the same exported launcher entry as a normal user; never relax app permissions.
        val monitor = ins.addMonitor("com.bob.recordinghelper.MainActivity", null, false)
        val activity = try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(
                "am start -W -n ${ctx.packageName}/.MainActivity")).use { it.readBytes() }
            monitor.waitForActivityWithTimeout(15_000) ?: error("Launcher activity was not observed")
        } finally { ins.removeMonitor(monitor) }
        var dialog: AlertDialog? = null
        try {
            ins.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                var saved: Pair<String, Int>? = null
                fun show(choice: String?, days: Int) = ImportChoiceDialog.show(activity, choice, days) { mode, n -> saved = mode to n; true }
                val first = show(null, 0).also { dialog = it }
                val group = first.window!!.decorView.findViewWithTag<RadioGroup>("import-choice-options")
                val field = group.rootView.findViewWithTag<EditText>("import-choice-days")
                val confirm = first.getButton(AlertDialog.BUTTON_POSITIVE)
                assertEquals(-1, group.checkedRadioButtonId); assertFalse(confirm.isEnabled)
                group.check(group.getChildAt(1).id)
                assertEquals("", field.text.toString()); assertFalse(confirm.isEnabled)
                for (invalid in listOf("0", "36501", "999999999999")) { field.setText(invalid); assertFalse(confirm.isEnabled) }
                field.setText("7"); assertTrue(confirm.isEnabled)
                confirm.performClick(); assertEquals("recent" to 7, saved); assertFalse(first.isShowing)

                saved = null
                val cancel = show(null, 0).also { dialog = it }
                cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                assertNull(saved)

                val remembered = show("recent", 14).also { dialog = it }
                val rg = remembered.window!!.decorView.findViewWithTag<RadioGroup>("import-choice-options")
                assertEquals(rg.getChildAt(1).id, rg.checkedRadioButtonId)
                assertEquals("14", rg.rootView.findViewWithTag<EditText>("import-choice-days").text.toString())
                rg.check(rg.getChildAt(3).id)
                remembered.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals("off" to 0, saved)

                saved = null
                val refused = ImportChoiceDialog.show(activity, "all", 0) { _, _ -> false }.also { dialog = it }
                refused.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue("Permission refusal must not dismiss/enable", refused.isShowing)
                refused.dismiss()
            }
            assertEquals("UI test changed live device settings", before, AutoImportSettings.status(ctx).toString())
        } finally {
            ins.runOnMainSync { dialog?.dismiss(); activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activity.finish() }
        }
    }
}
