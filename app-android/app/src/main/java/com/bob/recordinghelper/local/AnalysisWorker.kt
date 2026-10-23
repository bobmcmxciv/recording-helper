package com.bob.recordinghelper.local

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.*
import com.bob.recordinghelper.*
import com.bob.recordinghelper.llm.*
import java.util.concurrent.CopyOnWriteArrayList

/** Live state of local/remote note & todo extraction so UI screens can show progress, auto-refresh, and cancel. */
object AnalysisState {
    data class Snapshot(
        val running: Boolean,
        val activeId: String?,
        val phase: String,
        val startedAt: Long,
        val queuedIds: List<String>,
        val urgent: Boolean,
        val activeTitle: String = "",
        val modelLabel: String = "",
        val streamText: String = "",
    )

    private val lock = Any()
    private val queued = LinkedHashSet<String>()
    private val forcePolish = HashSet<String>()
    @Volatile private var activeId: String? = null
    @Volatile private var activeTitle: String = ""
    @Volatile private var modelLabel: String = ""
    @Volatile private var phase: String = ""
    @Volatile private var startedAt: Long = 0L
    @Volatile private var running: Boolean = false
    @Volatile private var urgent: Boolean = false
    @Volatile private var currentChunk: Int = 1
    @Volatile private var totalChunks: Int = 1
    @Volatile private var generatedChars: Int = 0
    private val streamBuf = StringBuilder()
    @Volatile private var streamPosted = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            running = running || activeId != null,
            activeId = activeId,
            phase = phase,
            startedAt = startedAt,
            queuedIds = queued.toList(),
            urgent = urgent,
            activeTitle = activeTitle,
            modelLabel = modelLabel,
            streamText = streamBuf.toString(),
        )
    }

    fun isQueued(id: String): Boolean = synchronized(lock) { id in queued }
    fun isActive(id: String): Boolean = activeId == id
    fun isQueuedOrActive(id: String): Boolean = isActive(id) || isQueued(id)
    fun isUrgent(): Boolean = urgent

    fun enqueuePriority(targetId: String?, forcePolishTarget: Boolean = false) {
        synchronized(lock) {
            urgent = true
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
            if (targetId != null && targetId.isNotBlank()) {
                if (forcePolishTarget) forcePolish.add(targetId)
                if (targetId != activeId) {
                    val reordered = LinkedHashSet<String>()
                    reordered.add(targetId)
                    reordered.addAll(queued)
                    queued.clear()
                    queued.addAll(reordered)
                }
            }
            if (!running && activeId == null) {
                phase = "已加入优先队列，正在准备整理模型…"
            }
        }
        notifyListeners()
    }

    fun consumeForcePolish(id: String): Boolean = synchronized(lock) { forcePolish.remove(id) }

    fun removePriority(id: String): Boolean {
        val removed = synchronized(lock) {
            forcePolish.remove(id)
            val r = queued.remove(id)
            if (queued.isEmpty() && activeId == null) {
                urgent = false
                running = false
                phase = ""
                activeTitle = ""
                streamBuf.setLength(0)
                startedAt = 0L
            }
            r
        }
        if (removed) notifyListeners()
        return removed
    }

    /** Orders explicitly queued recording IDs first (in queue order), followed by remaining candidates. */
    fun pollOrder(candidates: List<org.json.JSONObject>): List<org.json.JSONObject> = synchronized(lock) {
        if (queued.isEmpty()) return candidates
        val byId = candidates.associateBy { it.optString("id") }
        val result = ArrayList<org.json.JSONObject>(candidates.size)
        val seen = HashSet<String>()
        for (id in queued) {
            val match = byId[id]
            if (match != null && seen.add(id)) result.add(match)
        }
        for (c in candidates) {
            val id = c.optString("id")
            if (seen.add(id)) result.add(c)
        }
        result
    }

    internal fun onWorkerStarted(now: Boolean) {
        synchronized(lock) {
            running = true
            if (now) urgent = true
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
            if (phase.isBlank()) phase = "正在准备整理…"
        }
        notifyListeners()
    }

    internal fun onPhase(id: String?, text: String, title: String = "", model: String = "") {
        synchronized(lock) {
            running = true
            if (id != null && id != activeId) {
                activeId = id
                queued.remove(id)
                streamBuf.setLength(0)
                generatedChars = 0
                currentChunk = 1
                totalChunks = 1
                startedAt = System.currentTimeMillis()
            } else if (startedAt == 0L) {
                startedAt = System.currentTimeMillis()
            }
            if (title.isNotBlank()) activeTitle = title
            if (model.isNotBlank()) modelLabel = model
            phase = text
        }
        notifyListeners()
    }

    internal fun onPrefill(id: String?, chunk: Int, chunks: Int, text: String, title: String = "", model: String = "") {
        synchronized(lock) {
            running = true
            if (id != null && id != activeId) {
                activeId = id
                queued.remove(id)
                startedAt = System.currentTimeMillis()
            } else if (startedAt == 0L) {
                startedAt = System.currentTimeMillis()
            }
            currentChunk = chunk
            totalChunks = maxOf(chunks, 1)
            generatedChars = 0
            if (title.isNotBlank()) activeTitle = title
            if (model.isNotBlank()) modelLabel = model
            phase = text
        }
        notifyListeners()
    }

    internal fun onToken(id: String?, piece: String, updatePhase: Boolean = true) {
        if (piece.isEmpty()) return
        synchronized(lock) {
            running = true
            if (id != null && activeId == null) activeId = id
            streamBuf.append(piece)
            if (streamBuf.length > 4000) streamBuf.delete(0, streamBuf.length - 3200)
            if (updatePhase) {
                generatedChars += piece.length
                val s = streamBuf.toString()
                val thinking = s.lastIndexOf("<think>") > s.lastIndexOf("</think>")
                val stage = if (thinking) "正在深度思考推理" else "正在流式生成纪要与待办"
                phase = if (totalChunks > 1) {
                    "$stage（第 $currentChunk/$totalChunks 段 · 已生成 $generatedChars 字）…"
                } else {
                    "$stage（已生成 $generatedChars 字）…"
                }
            }
        }
        notifyListenersThrottled()
    }

    internal fun onStreamReplace(id: String?, text: String) {
        synchronized(lock) {
            if (id != null && activeId == null) activeId = id
            streamBuf.setLength(0)
            streamBuf.append(text.takeLast(3200))
        }
        notifyListenersThrottled()
    }

    internal fun onJobFinished(id: String) {
        synchronized(lock) {
            queued.remove(id)
            forcePolish.remove(id)
            if (activeId == id) {
                activeId = null
                activeTitle = ""
                streamBuf.setLength(0)
            }
            if (queued.isEmpty()) urgent = false
        }
        notifyListeners()
    }

    internal fun onWorkerFinished() {
        synchronized(lock) {
            running = false
            activeId = null
            activeTitle = ""
            streamBuf.setLength(0)
            if (queued.isEmpty()) {
                urgent = false
                phase = ""
                startedAt = 0L
            }
        }
        notifyListeners()
    }

    fun clearAll() {
        synchronized(lock) {
            queued.clear()
            forcePolish.clear()
            activeId = null
            activeTitle = ""
            streamBuf.setLength(0)
            running = false
            urgent = false
            phase = ""
            startedAt = 0L
        }
        notifyListeners()
    }

    fun statusLine(): String? {
        val s = snapshot()
        return when {
            s.activeId != null || s.running -> s.phase.ifBlank { "本机模型正在整理纪要与待办…" }
            s.queuedIds.isNotEmpty() -> "已排队 ${s.queuedIds.size} 条记录，等待整理模型…"
            else -> null
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyListeners() {
        runCatching {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper == null || Looper.myLooper() == mainLooper) {
                listeners.forEach { it() }
            } else {
                Handler(mainLooper).post { listeners.forEach { it() } }
            }
        }
    }

    private fun notifyListenersThrottled() {
        runCatching {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper == null) {
                listeners.forEach { it() }
            } else if (!streamPosted) {
                streamPosted = true
                Handler(mainLooper).postDelayed({
                    streamPosted = false
                    listeners.forEach { it() }
                }, 180)
            }
        }
    }
}

/** Turns pending transcripts into notes and todos, with the local model or, after explicit consent, an online one. */
class AnalysisWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    @Volatile private var active: TextModel? = null
    @Volatile private var polisher: Polisher? = null
    @Volatile private var cancelledByUser = false
    @Volatile var isNow: Boolean = false
        private set

    override fun doWork(): Result {
        val ctx = applicationContext
        val now = inputData.getBoolean("now", false) || AnalysisState.isUrgent()
        isNow = now
        val db = LocalStore.of(ctx)
        db.recoverAnalyzing(AnalysisState.snapshot().activeId)
        val remote = LlmSettings.remoteModel(ctx)
        val hasLocalLlm = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) != null
        if (remote == null && !hasLocalLlm) {
            if (!isPaused(ctx) && !isDownloadingLlm(ctx)) {
                val candidates = pendingCandidates(ctx, db)
                for (job in candidates) {
                    val id = job.getString("id")
                    val text = job.optString("text")
                    if (text.isNotBlank()) {
                        db.analyzed(id, job.optString("polished").ifBlank { text }, LocalAnalysis.quickExtract(text))
                    }
                    AnalysisState.onJobFinished(id)
                }
                if (candidates.isNotEmpty()) SyncWorker.schedule(ctx)
            }
            AnalysisState.clearAll()
            return Result.success()
        }
        if (shouldYieldBackground(ctx, now)) {
            if (!now) AnalysisState.onWorkerFinished()
            return Result.success()
        }
        val candidates = pendingCandidates(ctx, db)
        if (candidates.isEmpty()) {
            AnalysisState.clearAll()
            return Result.success()
        }
        val firstJob = candidates.first()
        val firstId = firstJob.getString("id")
        val firstTitle = firstJob.optString("title").ifBlank { "录音记录" }
        currentWorker = this
        AnalysisState.onWorkerStarted(now)
        try {
            if (remote != null) {
                if (LlmSettings.wifiOnly(ctx) && !LlmSettings.onUnmeteredNetwork(ctx)) return Result.retry()
                val remoteLabel = "在线模型 ${LlmSettings.model(ctx)}"
                val connMsg = "正在连接 $remoteLabel…"
                AnalysisState.onPhase(firstId, connMsg, title = firstTitle, model = remoteLabel)
                LiveAsr.llmBegin(firstTitle, remoteLabel, "提炼纪要与待办")
                LiveAsr.llmChunk(1, 1, connMsg)
                foreground(ctx, "在线 AI 整理", "$firstTitle · $connMsg")
                runJobs(ctx, db, remote, polish = false, title = "在线 AI 整理") {
                    !LlmSettings.remoteEnabled(ctx) || shouldYieldBackground(ctx, isNow)
                }
            } else {
                val file = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) ?: return Result.success()
                val localLabel = "本地大模型 ${file.nameWithoutExtension}"
                val sizeStr = Progress.human(file.length())
                val waitMsg = "正在等待模型空闲（准备使用 $localLabel）…"
                foreground(ctx, "本地 AI 整理", "$firstTitle · $waitMsg")
                AnalysisState.onPhase(firstId, waitMsg, title = firstTitle, model = localLabel)
                LiveAsr.llmBegin(firstTitle, localLabel, "准备本地整理模型")
                LiveAsr.llmChunk(1, 1, waitMsg)
                KeepAwake.hold(ctx, "本地 AI 整理") { ModelGate.hold {
                    if (stoppedOrCancelled() || LlmSettings.remoteEnabled(ctx) || shouldYieldBackground(ctx, isNow)) return@hold
                    try {
                        val loadMsg = "正在加载 $localLabel（$sizeStr · CPU 4 核准备中）…"
                        AnalysisState.onPhase(firstId, loadMsg, title = firstTitle, model = localLabel)
                        LiveAsr.llmChunk(1, 1, loadMsg)
                        Progress.notifyOngoing(ctx, "本地 AI 整理", "$firstTitle · $loadMsg", 0, 0, force = true)
                        LlmEngine.load(file).use { model ->
                            if (stoppedOrCancelled() || LlmSettings.remoteEnabled(ctx)) return@use
                            runJobs(ctx, db, model, polish = Prefs.polishMode(ctx) == Prefs.POLISH_LOCAL, title = "本地 AI 整理") {
                                LlmSettings.remoteEnabled(ctx) || shouldYieldBackground(ctx, isNow)
                            }
                        }
                    } catch (e: Exception) {
                        if (!stoppedOrCancelled()) {
                            LiveAsr.llmFinish("模型加载失败：${e.message ?: "未知错误"}")
                            pendingCandidates(ctx, db).forEach { db.status(it.getString("id"), "error", e.message ?: "模型加载失败") }
                        }
                    }
                } }
            }
            SyncWorker.schedule(ctx)
            return Result.success()
        } finally {
            if (currentWorker === this) currentWorker = null
            val aborted = stoppedOrCancelled()
            if (aborted) db.recoverAnalyzing()
            AnalysisState.onWorkerFinished()
            if (!aborted && !isPaused(ctx)) {
                if (Prefs.autoScan(ctx) && RecordingQueue.of(ctx).pending() > 0 && !AnalysisState.isUrgent()) {
                    RecordingQueueWorker.schedule(ctx)
                } else if (pendingCandidates(ctx, db).isNotEmpty()) {
                    val nextUrgent = AnalysisState.isUrgent()
                    if (nextUrgent || !shouldYieldBackground(ctx, false)) {
                        schedule(ctx, now = nextUrgent)
                    }
                }
            }
        }
    }

    private fun stoppedOrCancelled(): Boolean = isStopped || cancelledByUser

    private fun pendingCandidates(ctx: Context, db: LocalStore): List<org.json.JSONObject> {
        val owner = LocalSettings.device(ctx)
        val raw = db.all("recording").filter {
            it.optString("status") in listOf("pending", "analyzing") && it.optString("ownerDevice") == owner
        }
        return AnalysisState.pollOrder(raw)
    }

    private fun runJobs(ctx: Context, db: LocalStore, model: TextModel, polish: Boolean, title: String, yield: () -> Boolean) {
        active = model
        val modelLabel = when (model) {
            is LlmEngine -> "本地大模型 ${model.name}"
            is RemoteTextModel -> "在线模型 ${LlmSettings.model(ctx)}"
            else -> "AI 模型"
        }
        val maxBatch = if (isNow) 8 else 2
        var processed = 0
        try {
            while (!stoppedOrCancelled() && !yield() && processed < maxBatch) {
                val job = pendingCandidates(ctx, db).firstOrNull() ?: break
                val id = job.getString("id")
                val recTitle = job.optString("title").ifBlank { "录音记录" }
                if (db.get(id)?.optString("status") !in listOf("pending", "analyzing")) {
                    AnalysisState.onJobFinished(id)
                    continue
                }
                db.status(id, "analyzing")
                try {
                    val text = job.getString("text")
                    val shouldPolish = (polish && model is LlmEngine) || AnalysisState.consumeForcePolish(id)
                    val polished = if (shouldPolish) {
                        val initPolishMsg = "正在本地润色校对：$recTitle"
                        AnalysisState.onPhase(id, initPolishMsg, title = recTitle, model = modelLabel)
                        LiveAsr.polishBegin(recTitle, modelLabel)
                        Progress.notifyOngoing(ctx, title, "$recTitle · 正在本地润色校对", 0, 0, force = true)
                        val out = Polisher(model).also { polisher = it }.polish(text) { p ->
                            val polishPhase = if (p.partial.isEmpty()) {
                                "正在本地润色校对（第 ${p.chunk + 1}/${p.chunks} 段 · CPU 4 核预填充原文中）…"
                            } else {
                                "正在本地润色校对（第 ${p.chunk + 1}/${p.chunks} 段 · 已生成 ${p.partial.length} 字）…"
                            }
                            AnalysisState.onPhase(id, polishPhase, title = recTitle, model = modelLabel)
                            LiveAsr.polishProgress(p.chunk, p.chunks, p.partial, polishPhase)
                            if (p.partial.isNotEmpty()) AnalysisState.onStreamReplace(id, p.partial)
                            Progress.notifyOngoing(ctx, title, "$recTitle · $polishPhase", p.chunks, p.chunk + 1)
                        }.text
                        LiveAsr.polishDone("润色完成", out)
                        out
                    } else job.optString("polished").ifBlank { text }
                    polisher = null
                    if (stoppedOrCancelled() || cancelTargetId == id) throw InterruptedException("分析已取消")
                    LiveAsr.llmBegin(recTitle, modelLabel, "提炼纪要与待办")
                    var headerPending = false
                    val result = LocalAnalysis.analyze(
                        model = model,
                        text = text,
                        date = job.optString("occurredAt"),
                        onProgress = { idx, total ->
                            headerPending = true
                            val phaseText = if (total > 1) "正在准备提炼纪要与待办（第 $idx / $total 段）…" else "正在准备提炼纪要与待办…"
                            AnalysisState.onPhase(id, phaseText, title = recTitle, model = modelLabel)
                            LiveAsr.llmChunk(idx, total, phaseText)
                            Progress.notifyOngoing(ctx, title, "$recTitle · $phaseText", total, idx - 1, force = true)
                        },
                        onPrefill = { idx, total, promptTokens ->
                            headerPending = false
                            val prefillText = if (model is LlmEngine) {
                                if (total > 1) "正在预填充阅读第 $idx/$total 段原文（约 $promptTokens tokens · CPU 4 核计算中）…"
                                else "正在预填充阅读原文（约 $promptTokens tokens · CPU 4 核计算中）…"
                            } else {
                                if (total > 1) "正在发送第 $idx/$total 段并等待云端首包（约 $promptTokens tokens）…"
                                else "正在发送请求并等待云端首包（约 $promptTokens tokens）…"
                            }
                            AnalysisState.onPrefill(id, idx, total, prefillText, title = recTitle, model = modelLabel)
                            LiveAsr.llmPrefill(idx, total, prefillText)
                            Progress.notifyOngoing(ctx, title, "$recTitle · $prefillText", total, idx - 1, force = true)
                        },
                        onToken = { piece ->
                            val isHeader = headerPending
                            AnalysisState.onToken(id, piece, updatePhase = !isHeader)
                            LiveAsr.llmToken(piece)
                            if (!isHeader) {
                                Progress.notifyOngoing(ctx, title, "$recTitle · ${AnalysisState.snapshot().phase}", 0, 0)
                            }
                        },
                        stopped = { stoppedOrCancelled() || cancelTargetId == id }
                    )
                    if (stoppedOrCancelled() || cancelTargetId == id) throw InterruptedException("分析已取消")
                    val statsText = describeStats(ctx, model)
                    result.put("analysisTrace", result.optString("rawTrace"))
                        .put("analysisModel", modelLabel)
                        .put("analysisStats", statsText)
                    db.analyzed(id, polished, result)
                    LiveAsr.llmFinish(statsText, result.optString("summary"))
                    AnalysisState.onJobFinished(id)
                    processed++
                } catch (_: InterruptedException) {
                    if (cancelTargetId == id) cancelTargetId = null
                    LiveAsr.llmFinish("已取消整理")
                    db.status(id, "pending")
                    AnalysisState.onJobFinished(id)
                    if (stoppedOrCancelled()) break
                } catch (e: Exception) {
                    if (stoppedOrCancelled() || cancelTargetId == id) {
                        cancelTargetId = null
                        LiveAsr.llmFinish("已取消整理")
                        db.status(id, "pending")
                        AnalysisState.onJobFinished(id)
                        if (stoppedOrCancelled()) break
                    } else {
                        val msg = e.message ?: "分析失败"
                        LiveAsr.llmFinish("整理未完成：$msg")
                        db.status(id, "error", msg)
                        AnalysisState.onJobFinished(id)
                        processed++
                    }
                }
            }
        } finally {
            polisher = null
            active = null
        }
    }

    private fun describeStats(ctx: Context, model: TextModel): String = when (model) {
        is LlmEngine -> {
            val st = runCatching { model.lastStats() }.getOrNull()
            if (st != null && st.generatedTokens > 0) {
                val tokPerSec = if (st.generateMs > 0) st.generatedTokens * 1000.0 / st.generateMs else 0.0
                "已完成 · 输入 ${st.promptTokens} / 输出 ${st.generatedTokens} tokens · %.1f tok/s".format(tokPerSec)
            } else "已完成"
        }
        else -> {
            val u = LlmSettings.lastUsage(ctx)
            if (u.isNotBlank()) "已完成 · $u" else "已完成"
        }
    }

    private fun foreground(ctx: Context, title: String, text: String) {
        val n = Progress.ongoing(ctx, title, text, 0, 0, true)
        try { setForegroundAsync(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(220, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(220, n)).get() } catch (_: Exception) {}
    }

    internal fun stopImmediately() {
        cancelledByUser = true
        polisher?.cancel()
        active?.cancel()
    }

    override fun onStopped() {
        stopImmediately()
    }

    companion object {
        private const val WORK_NAME = "local-analysis"
        private const val LEGACY_NOW_WORK = "local-analysis-now"
        @Volatile private var currentWorker: AnalysisWorker? = null
        @Volatile private var cancelTargetId: String? = null

        fun isPaused(ctx: Context): Boolean = LocalSettings.prefs(ctx).getBoolean("analysisPaused", false)

        fun setPaused(ctx: Context, paused: Boolean) {
            LocalSettings.prefs(ctx).edit().putBoolean("analysisPaused", paused).commit()
        }

        fun canAnalyze(ctx: Context): Boolean =
            LlmSettings.remoteEnabled(ctx) || LlmEngine.modelIn(LocalAsr.llmDir(ctx)) != null

        fun isDownloadingLlm(ctx: Context): Boolean = runCatching {
            WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("model-install").get()
                .any { !it.state.isFinished && (it.tags.contains("slot:model.gguf") || it.progress.getString("slot") == "model.gguf") }
        }.getOrDefault(false)

        /** Pure predicate testable without WorkManager: background analysis must yield when autoScan is paused or transcription queue is non-empty. */
        internal fun shouldYieldBackground(
            busy: Boolean,
            analysisPaused: Boolean,
            now: Boolean,
            urgent: Boolean,
            autoScan: Boolean,
            pendingTranscriptions: Int,
        ): Boolean {
            if (busy || analysisPaused) return true
            if (now || urgent) return false
            return !autoScan || pendingTranscriptions > 0
        }

        private fun shouldYieldBackground(ctx: Context, now: Boolean): Boolean = shouldYieldBackground(
            busy = MicState.busy || VoiceRelationState.busy,
            analysisPaused = isPaused(ctx),
            now = now,
            urgent = AnalysisState.isUrgent(),
            autoScan = Prefs.autoScan(ctx),
            pendingTranscriptions = RecordingQueue.of(ctx).pending(),
        )

        /** Explicit user trigger ("立即整理"): prioritizes [targetId] if provided, preempts background queue, and starts immediately. */
        fun requestNow(ctx: Context, targetId: String? = null, forcePolish: Boolean = false) {
            val app = ctx.applicationContext
            setPaused(app, false)
            cancelTargetId = null
            if (!targetId.isNullOrBlank()) {
                LocalStore.of(app).status(targetId, "pending")
            }
            AnalysisState.enqueuePriority(targetId, forcePolish)
            val runningWorker = currentWorker
            if (runningWorker != null && !runningWorker.stoppedOrCancelled()) {
                runningWorker.isNow = true
                if (targetId != null && AnalysisState.isActive(targetId)) return
                // Running worker will pick up the prioritized recording on its next loop iteration;
                // if it is still waiting on or running a background job, keep it alive unless stopped.
                return
            }
            enqueue(app, now = true, policy = ExistingWorkPolicy.REPLACE)
        }

        /** Cancels either a specific recording's analysis/queue entry or all active/queued analysis work. */
        fun cancelActive(ctx: Context, targetId: String? = null, pauseBackground: Boolean = false) {
            val app = ctx.applicationContext
            if (pauseBackground) setPaused(app, true)
            if (targetId != null) {
                val wasQueued = AnalysisState.removePriority(targetId)
                val wasActive = AnalysisState.isActive(targetId)
                if (wasQueued && !wasActive && AnalysisState.snapshot().running) {
                    LocalStore.of(app).status(targetId, "pending")
                    return
                }
                if (wasActive && AnalysisState.snapshot().queuedIds.isNotEmpty() && !pauseBackground) {
                    cancelTargetId = targetId
                    currentWorker?.polisher?.cancel()
                    currentWorker?.active?.cancel()
                    LocalStore.of(app).status(targetId, "pending")
                    return
                }
            }
            cancelTargetId = null
            AnalysisState.clearAll()
            val liveSnap = LiveAsr.snapshot()
            if (liveSnap.llmActive) LiveAsr.llmFinish("已取消整理")
            if (liveSnap.polishActive) LiveAsr.polishDone("已取消润色")
            currentWorker?.stopImmediately()
            currentWorker = null
            val wm = WorkManager.getInstance(app)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(LEGACY_NOW_WORK)
            LocalStore.of(app).recoverAnalyzing()
        }

        fun schedule(ctx: Context, now: Boolean = false) {
            val app = ctx.applicationContext
            if (now) {
                requestNow(app, null)
                return
            }
            val canRunQuickExtract = !canAnalyze(app) && !isPaused(app) && !isDownloadingLlm(app)
            if (!canRunQuickExtract && shouldYieldBackground(app, false)) return
            enqueue(app, now = false, policy = ExistingWorkPolicy.KEEP)
        }

        private fun enqueue(ctx: Context, now: Boolean, policy: ExistingWorkPolicy) {
            val remote = LlmSettings.remoteEnabled(ctx)
            val hasLocalLlm = LlmEngine.modelIn(LocalAsr.llmDir(ctx)) != null
            val constraints = Constraints.Builder().apply {
                if (remote) setRequiredNetworkType(if (LlmSettings.wifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                else if (!now && hasLocalLlm) setRequiresCharging(true)
            }.build()
            val builder = OneTimeWorkRequestBuilder<AnalysisWorker>()
                .setInputData(workDataOf("now" to now))
                .setConstraints(constraints)
            if (now) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(LEGACY_NOW_WORK)
            wm.enqueueUniqueWork(WORK_NAME, policy, builder.build())
        }
    }
}
