package com.bob.recordinghelper

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bob.recordinghelper.databinding.ActivityMainBinding
import com.bob.recordinghelper.local.AutoImportSettings
import com.bob.recordinghelper.ui.HomeFragment
import com.bob.recordinghelper.ui.LibraryFragment
import com.bob.recordinghelper.ui.MeFragment
import com.bob.recordinghelper.ui.TodosFragment
import com.bob.recordinghelper.ui.Ui

/** Native shell: four tabs, no WebView, no required server. */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && com.bob.recordinghelper.local.Onboarding.shouldShow(this)) {
            startActivity(com.bob.recordinghelper.ui.OnboardingActivity.intent(this)); finish(); return
        }
        enableEdgeToEdge()
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        val rail = b.nav as? com.google.android.material.navigationrail.NavigationRailView
        if (rail != null) Ui.railInsets(b.root, rail, b.container) else Ui.insets(b.root, top = b.container, bottom = b.nav)
        b.nav.setOnItemSelectedListener { item -> show(item.itemId); true }
        b.nav.setOnItemReselectedListener { }
        // The first menu item is pre-selected, so selecting it again never fires the listener: attach explicitly.
        if (savedInstanceState == null) {
            val tab = intent.getIntExtra("tab", R.id.nav_home)
            b.nav.selectedItemId = tab; show(tab)
        }
    }

    override fun onResume() {
        super.onResume()
        AutoImportSettings.requestScan(this)
    }

    fun open(tabId: Int) { b.nav.selectedItemId = tabId }

    private fun show(id: Int) {
        val tag = "tab-$id"
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.fragments.forEach { if (it.tag != tag) tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing != null) tx.show(existing) else tx.add(R.id.container, create(id), tag)
        tx.commitNowAllowingStateLoss()
    }

    private fun create(id: Int): Fragment = when (id) {
        R.id.nav_library -> LibraryFragment()
        R.id.nav_todos -> TodosFragment()
        R.id.nav_me -> MeFragment()
        else -> HomeFragment()
    }
}
