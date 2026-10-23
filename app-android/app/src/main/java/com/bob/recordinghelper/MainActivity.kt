package com.bob.recordinghelper

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bob.recordinghelper.databinding.ActivityMainBinding
import com.bob.recordinghelper.llm.LlmSettings
import com.bob.recordinghelper.local.AnalysisState
import com.bob.recordinghelper.local.AnalysisWorker
import com.bob.recordinghelper.local.AutoImportSettings
import com.bob.recordinghelper.local.DigestWorker
import com.bob.recordinghelper.ui.HomeFragment
import com.bob.recordinghelper.ui.LibraryFragment
import com.bob.recordinghelper.ui.MeFragment
import com.bob.recordinghelper.ui.TodosFragment
import com.bob.recordinghelper.ui.Ui

/** Native shell: four tabs, no WebView, no required server. */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val modelListener = { renderModelWorkingBar() }
    private val modelTicker = object : Runnable {
        override fun run() {
            val s = LiveAsr.snapshot()
            val a = AnalysisState.snapshot()
            if (s.active || s.llmActive || s.polishActive || a.running || a.queuedIds.isNotEmpty()) {
                renderModelWorkingBar()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("debugShowWhenLocked", false) && android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (savedInstanceState == null && com.bob.recordinghelper.local.Onboarding.shouldShow(this)) {
            startActivity(com.bob.recordinghelper.ui.OnboardingActivity.intent(this)); finish(); return
        }
        enableEdgeToEdge()
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        val rail = b.nav as? com.google.android.material.navigationrail.NavigationRailView
        val contentRoot = b.root.findViewById<View>(R.id.contentColumn) ?: b.container
        if (rail != null) Ui.railInsets(b.root, rail, contentRoot) else Ui.insets(b.root, top = b.container, bottom = b.nav)
        val openLive = {
            val activeId = AnalysisState.snapshot().activeId?.takeIf { !it.startsWith("digest:") && !it.startsWith("trial:") }
            startActivity(TranscribeLiveActivity.intent(this, activeId))
        }
        b.modelWorkingBar.setOnClickListener { openLive() }
        b.btnModelWorkingView.setOnClickListener { openLive() }
        b.btnModelWorkingStop.setOnClickListener { stopActiveModelWork() }
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
        val app = applicationContext
        Thread { runCatching { com.bob.recordinghelper.local.StateMirror.hydrateIfNeeded(app) } }.start()
        AutoImportSettings.requestScan(this)
        LiveAsr.addListener(modelListener)
        AnalysisState.addListener(modelListener)
        renderModelWorkingBar()
    }

    override fun onPause() {
        handler.removeCallbacks(modelTicker)
        LiveAsr.removeListener(modelListener)
        AnalysisState.removeListener(modelListener)
        super.onPause()
    }

    private fun renderModelWorkingBar() {
        if (!::b.isInitialized) return
        val s = LiveAsr.snapshot()
        val a = AnalysisState.snapshot()
        val llmBusy = a.running || a.queuedIds.isNotEmpty() || s.llmActive || s.polishActive
        when {
            llmBusy -> {
                b.modelWorkingBar.visibility = View.VISIBLE
                val modelName = s.llmModel.ifBlank {
                    a.modelLabel.ifBlank {
                        s.polishModel.ifBlank {
                            if (LlmSettings.remoteEnabled(this)) "在线模型 ${LlmSettings.model(this)}" else "本地大模型"
                        }
                    }
                }
                val startMs = when {
                    s.llmActive && s.llmStartedAt > 0L -> s.llmStartedAt
                    s.polishActive && s.polishStartedAt > 0L -> s.polishStartedAt
                    a.startedAt > 0L -> a.startedAt
                    else -> 0L
                }
                val elapsedSec = if (startMs > 0L) ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(1L) else 0L
                b.tvModelWorkingTitle.text = "$modelName 正在工作" + if (elapsedSec > 0L) " · 已用时 ${elapsedSec} 秒" else ""
                val targetTitle = s.llmTitle.ifBlank { a.activeTitle.ifBlank { s.polishTitle } }
                val phase = when {
                    a.phase.isNotBlank() -> a.phase
                    s.llmStatus.isNotBlank() -> s.llmStatus
                    s.polishStatus.isNotBlank() -> s.polishStatus
                    a.queuedIds.isNotEmpty() -> "已排队 ${a.queuedIds.size} 条记录，正在准备整理…"
                    else -> "正在整理…"
                }
                val stream = s.llmText.ifBlank { a.streamText.ifBlank { s.polishText } }.replace('\n', ' ').trim().takeLast(140)
                val head = if (targetTitle.isNotBlank() && !phase.contains(targetTitle)) "$targetTitle · $phase" else phase
                b.tvModelWorkingSub.text = if (stream.isNotBlank()) "$head\n实时输出：$stream" else head
                handler.removeCallbacks(modelTicker)
                handler.postDelayed(modelTicker, 1000)
            }
            s.active -> {
                b.modelWorkingBar.visibility = View.VISIBLE
                val elapsedSec = if (s.startedAt > 0L) ((System.currentTimeMillis() - s.startedAt) / 1000L).coerceAtLeast(1L) else 0L
                val eng = TranscribeLiveActivity.engineName(s.engine)
                b.tvModelWorkingTitle.text = "语音模型（$eng）正在转写" + if (elapsedSec > 0L) " · 已用时 ${elapsedSec} 秒" else ""
                val filePart = s.title + if (s.files > 1) "（第 ${s.fileNo}/${s.files} 个）" else ""
                val lastPiece = s.rows.lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
                b.tvModelWorkingSub.text = if (lastPiece.isNotBlank()) "$filePart · $lastPiece" else "$filePart · ${s.phase}"
                handler.removeCallbacks(modelTicker)
                handler.postDelayed(modelTicker, 1000)
            }
            else -> {
                b.modelWorkingBar.visibility = View.GONE
                handler.removeCallbacks(modelTicker)
            }
        }
    }

    private fun stopActiveModelWork() {
        val s = LiveAsr.snapshot()
        val a = AnalysisState.snapshot()
        val app = applicationContext
        Thread {
            if (a.running || a.queuedIds.isNotEmpty() || s.llmActive || s.polishActive) {
                AnalysisWorker.cancelActive(app)
                DigestWorker.cancel(app)
            }
            if (s.active) {
                runCatching { AutoImportSettings.pause(app, true) }
            }
            runOnUiThread {
                Ui.toast(this, "已停止当前模型任务")
                renderModelWorkingBar()
            }
        }.start()
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
