package com.bob.recordinghelper.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.util.Pair
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkManager
import com.bob.recordinghelper.R
import com.bob.recordinghelper.databinding.FragmentTodosBinding
import com.bob.recordinghelper.databinding.ItemSectionHeaderBinding
import com.bob.recordinghelper.databinding.ItemTodoBinding
import com.bob.recordinghelper.databinding.ItemTodoSummaryBinding
import com.bob.recordinghelper.llm.LlmEngine
import com.bob.recordinghelper.llm.LlmSettings
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.local.*
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

class TodosFragment : TabFragment() {
    private var _b: FragmentTodosBinding? = null
    private val b get() = _b!!
    private var todos: List<JSONObject> = emptyList()
    private var recordings: List<JSONObject> = emptyList()
    private var recordingDates: Map<String, LocalDate> = emptyMap()
    private var customRange: kotlin.Pair<LocalDate, LocalDate>? = null
    private var category = "all"
    private var status = R.id.filterOpen
    private var multi = false
    private val selected = LinkedHashSet<String>()
    private var digestKey = ""
    private var digesting = false
    private val digestHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val digestTicker = object : Runnable {
        override fun run() {
            if (_b != null && (digesting || AnalysisState.isActive("digest:$digestKey"))) {
                apply()
                digestHandler.postDelayed(this, 1000)
            }
        }
    }
    private val analysisListener = {
        if (digesting || AnalysisState.isActive("digest:$digestKey")) {
            apply()
            digestHandler.removeCallbacks(digestTicker)
            digestHandler.postDelayed(digestTicker, 1000)
        }
    }

    private val adapter = TodoAdapter(
        onToggle = { id, checked -> mutate { LocalStore.of(it).setDone(id, checked) } },
        onOpen = { id -> if (multi) toggleSelect(id) else TodoEditSheet.newInstance(id).show(childFragmentManager, "todo") },
        onLongPress = { id -> if (!multi) { multi = true; selected.clear() }; toggleSelect(id) },
        onSource = { recordingId -> startActivity(RecordingDetailActivity.intent(requireContext(), recordingId)) },
        onCategory = { id, next -> mutate { LocalStore.of(it).setCategory(id, next) } },
        onSummaryRefresh = { refreshDigest() },
        onOpenTrace = { startActivity(com.bob.recordinghelper.TranscribeLiveActivity.intent(requireContext())) },
        onCategoryFilter = { category = it; apply() },
        onStatusFilter = { status = it; apply() },
        isSelected = { selected.contains(it) },
        multiMode = { multi },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentTodosBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val columns = if (Ui.sidePane(b.root)) 1 else Ui.gridColumns(requireContext())
        b.list.layoutManager = if (columns > 1) androidx.recyclerview.widget.GridLayoutManager(requireContext(), columns).apply {
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == 1) 1 else columns }
        } else LinearLayoutManager(requireContext())
        Ui.column(b.root, if (Ui.sidePane(b.root)) 1440 else Ui.gridMaxDp(requireContext()))
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.fab.setOnClickListener { TodoEditSheet.newInstance(null).show(childFragmentManager, "todo") }
        b.timeFilters.setOnCheckedStateChangeListener { _, ids ->
            if (ids.firstOrNull() == R.id.timeCustom) pickRange() else apply()
        }
        b.btnMulti.setOnClickListener { multi = !multi; selected.clear(); apply() }
        b.btnBatchDone.setOnClickListener { batch("完成") { s, id -> s.setDone(id, true) } }
        b.btnBatchPostpone.setOnClickListener { postpone(selected.toList()) }
        b.btnBatchCategory.setOnClickListener { batchCategory() }
        b.btnBatchIgnore.setOnClickListener {
            if (selected.isEmpty()) { toast("请先选择待办"); return@setOnClickListener }
            MaterialAlertDialogBuilder(requireContext()).setTitle("忽略 ${selected.size} 件待办？").setMessage("忽略后从列表移除，原录音保持不变。")
                .setPositiveButton("忽略") { _, _ -> batch("忽略") { s, id -> s.delete(id) } }.setNegativeButton("取消", null).show()
        }
        childFragmentManager.setFragmentResultListener("changed", viewLifecycleOwner) { _, _ -> refresh() }
        ItemTouchHelper(swipeCallback()).attachToRecyclerView(b.list)
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { if (dy > 8) b.fab.shrink() else if (dy < -8) b.fab.extend() }
        })
    }

    override fun onResume() {
        super.onResume()
        AnalysisState.addListener(analysisListener)
        if (digesting || AnalysisState.isActive("digest:$digestKey")) {
            digestHandler.removeCallbacks(digestTicker)
            digestHandler.postDelayed(digestTicker, 1000)
        }
    }

    override fun onPause() {
        digestHandler.removeCallbacks(digestTicker)
        AnalysisState.removeListener(analysisListener)
        super.onPause()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    override fun refresh() {
        if (_b == null) return
        val ctx = requireContext().applicationContext
        load({
            val store = LocalStore.of(ctx)
            val allRecs = store.all("recording")
            val dates = allRecs.associate { it.getString("id") to (Ui.localDate(it.optString("occurredAt")) ?: LocalDate.now()) }
            Triple(store.all("todo"), allRecs, dates)
        }) { (rows, recs, dates) -> todos = rows; recordings = recs; recordingDates = dates; apply() }
    }

    // ---------- filters ----------
    private fun range(): kotlin.Pair<LocalDate, LocalDate>? {
        val today = LocalDate.now()
        return when (b.timeFilters.checkedChipId) {
            R.id.timeToday -> today to today
            R.id.timeWeek -> today.with(WeekFields.of(Locale.CHINA).dayOfWeek(), 1) to today.with(WeekFields.of(Locale.CHINA).dayOfWeek(), 7)
            R.id.timeMonth -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
            R.id.timeCustom -> customRange ?: (today.minusDays(6) to today)
            else -> null
        }
    }

    private fun rangeLabel(r: kotlin.Pair<LocalDate, LocalDate>?): String {
        if (r == null) return "全部时间"
        val f = DateTimeFormatter.ofPattern("M月d日")
        return when (b.timeFilters.checkedChipId) {
            R.id.timeToday -> "今天"; R.id.timeWeek -> "本周"; R.id.timeMonth -> "本月"
            else -> if (r.first == r.second) r.first.format(f) else "${r.first.format(f)}–${r.second.format(f)}"
        }
    }

    private fun sourceDate(t: JSONObject): LocalDate = recordingDates[t.optString("recordingId")]
        ?: Instant.ofEpochMilli(t.optLong("createdAt", System.currentTimeMillis())).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun pickRange() {
        val picker = MaterialDatePicker.Builder.dateRangePicker().setTitleText("按来源录音时间筛选").build()
        picker.addOnPositiveButtonClickListener { sel: Pair<Long, Long> ->
            customRange = Instant.ofEpochMilli(sel.first).atZone(ZoneOffset.UTC).toLocalDate() to Instant.ofEpochMilli(sel.second).atZone(ZoneOffset.UTC).toLocalDate()
            apply()
        }
        picker.addOnNegativeButtonClickListener { if (customRange == null) b.timeFilters.check(R.id.timeWeek) }
        picker.show(parentFragmentManager, "range")
    }

    private fun apply() {
        if (_b == null) return
        val r = range()
        b.tvRange.text = "按来源录音时间筛选 · " + rangeLabel(r)
        val inRange = todos.filter { r == null || sourceDate(it).let { d -> !d.isBefore(r.first) && !d.isAfter(r.second) } }
        val open = inRange.filter { !it.optBoolean("done") }
        val overdue = open.count { DueDates.state(it.optString("due")) == DueDates.State.OVERDUE }
        val byCat = { c: String -> open.count { LocalAnalysis.category(it.optString("category")) == c } }
        val filtered = inRange.filter { category == "all" || LocalAnalysis.category(it.optString("category")) == (if (category == "none") "" else category) }
        val byDue = compareBy<JSONObject> { DueDates.sortKey(it.optString("due")) }.thenByDescending { it.optLong("createdAt") }

        digestKey = "${rangeLabel(r)}:${r?.first}:${r?.second}"
        val digest = DigestWorker.cached(requireContext(), digestKey)
        val snap = AnalysisState.snapshot()
        val activeDigest = digesting || snap.activeId == "digest:$digestKey"
        val recsInRange = recordings.filter { rec ->
            r == null || (Ui.localDate(rec.optString("occurredAt")) ?: LocalDate.now()).let { d -> !d.isBefore(r.first) && !d.isAfter(r.second) }
        }
        val liveOverview = if (activeDigest) {
            val elapsed = if (snap.startedAt > 0L) ((System.currentTimeMillis() - snap.startedAt) / 1000L).coerceAtLeast(1L) else 0L
            val phaseLine = if (snap.activeId == "digest:$digestKey" && snap.phase.isNotBlank()) snap.phase else "正在准备整理模型，生成本时段重点总结…"
            val clock = if (elapsed > 0L) "（已用时 ${elapsed} 秒）" else ""
            val stream = if (snap.activeId == "digest:$digestKey") snap.streamText.trim() else ""
            if (stream.isNotBlank()) "$phaseLine$clock\n\n$stream"
            else "$phaseLine$clock\n点击可全屏查看模型实时推理过程。"
        } else digest?.optString("overview").orEmpty()
        val sourceLine = if (activeDigest) {
            val elapsed = if (snap.startedAt > 0L) ((System.currentTimeMillis() - snap.startedAt) / 1000L).coerceAtLeast(1L) else 0L
            listOfNotNull(
                snap.modelLabel.takeIf { it.isNotBlank() },
                if (elapsed > 0L) "已用时 ${elapsed} 秒" else null,
                "点击查看实时推理过程",
            ).joinToString(" · ")
        } else {
            val src = digest?.optString("source").orEmpty()
            val st = digest?.optString("stats").orEmpty()
            listOf(src, st, if (src.isNotBlank()) "点击查看推理过程" else "").filter { it.isNotBlank() }.joinToString(" · ")
        }
        val summary = TodoAdapter.Summary(
            title = "${rangeLabel(r)} · ${open.size} 件待办 · ${recsInRange.size} 份录音",
            counts = "公事 ${byCat("work")} · 私事 ${byCat("personal")} · 未分类 ${byCat("")}" + if (overdue > 0) " · 逾期 $overdue" else "",
            overview = liveOverview,
            error = if (activeDigest) "" else digest?.optString("error").orEmpty(),
            source = sourceLine,
            first = if (activeDigest) emptyList() else digest?.optJSONArray("first")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            category = category, status = status, busy = activeDigest,
        )
        val rows = ArrayList<TodoAdapter.Row>()
        rows.add(TodoAdapter.Row.Header2(summary))
        fun section(title: String, items: List<JSONObject>) {
            if (items.isEmpty()) return
            rows.add(TodoAdapter.Row.Header(title, items.size))
            items.forEach { rows.add(TodoAdapter.Row.Item(it)) }
        }
        val openF = filtered.filter { !it.optBoolean("done") }
        val today = openF.filter { DueDates.state(it.optString("due")).let { s -> s == DueDates.State.OVERDUE || s == DueDates.State.TODAY } }
        when (status) {
            R.id.filterReview -> section("待核对的 AI 建议", openF.filter { !it.optBoolean("reviewed") }.sortedWith(byDue))
            R.id.filterDone -> section("已完成", filtered.filter { it.optBoolean("done") }.sortedByDescending { it.optLong("createdAt") }.take(200))
            else -> {
                section("今日必办", today.sortedWith(byDue))
                section("待核对", openF.filter { !it.optBoolean("reviewed") && it !in today }.sortedWith(byDue))
                section("其他未完成", openF.filter { it.optBoolean("reviewed") && it !in today }.sortedWith(byDue))
            }
        }
        if (rows.size == 1) rows.add(TodoAdapter.Row.Empty(if (todos.isEmpty()) "暂时没有待办" else "这个范围没有待办",
            if (todos.isEmpty()) "整理录音后，明确的行动项会出现在这里。也可以手动添加。" else "试试其他时间或分类，也可以去录音里补充整理。"))
        adapter.submitList(rows)
        b.empty.root.visibility = View.GONE
        b.btnMulti.text = if (multi) "退出多选" else "多选"
        b.batchBar.visibility = if (multi) View.VISIBLE else View.GONE
        b.tvSelected.text = "已选 ${selected.size} 件"
        b.fab.visibility = if (multi) View.GONE else View.VISIBLE
    }

    // ---------- actions ----------
    private fun mutate(work: (android.content.Context) -> Unit) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching { work(ctx); SyncWorker.schedule(ctx) } }; refresh() }
    }

    private fun toggleSelect(id: String) { if (!selected.add(id)) selected.remove(id); apply() }

    private fun batch(label: String, work: (LocalStore, String) -> Unit) {
        if (selected.isEmpty()) { toast("请先选择待办"); return }
        val ids = selected.toList()
        mutate { ctx -> val s = LocalStore.of(ctx); ids.forEach { work(s, it) } }
        toast("已$label ${ids.size} 件"); selected.clear(); multi = false
    }

    private fun batchCategory() {
        if (selected.isEmpty()) { toast("请先选择待办"); return }
        MaterialAlertDialogBuilder(requireContext()).setTitle("修改 ${selected.size} 件待办的分类").setItems(arrayOf("公事", "私事", "未分类")) { _, which ->
            val value = listOf("work", "personal", "")[which]
            batch("改分类") { s, id -> s.setCategory(id, value) }
        }.setNegativeButton("取消", null).show()
    }

    private fun postpone(ids: List<String>) {
        if (ids.isEmpty()) { toast("请先选择待办"); return }
        val tomorrow = LocalDate.now().plusDays(1); val nextWeek = LocalDate.now().plusDays(7)
        val f = DateTimeFormatter.ofPattern("M月d日")
        MaterialAlertDialogBuilder(requireContext()).setTitle("推迟到什么时候？")
            .setItems(arrayOf("明天 · ${tomorrow.format(f)}", "下周 · ${nextWeek.format(f)}", "自定义日期")) { _, which ->
                when (which) {
                    0 -> setDue(ids, tomorrow.toString()); 1 -> setDue(ids, nextWeek.toString())
                    else -> MaterialDatePicker.Builder.datePicker().setTitleText("推迟到").build().apply {
                        addOnPositiveButtonClickListener { ms -> setDue(ids, Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    }.show(parentFragmentManager, "postpone")
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun setDue(ids: List<String>, due: String) {
        mutate { ctx -> val s = LocalStore.of(ctx); ids.forEach { s.setDue(it, due) } }
        toast("已推迟，来源录音时间保持不变"); selected.clear(); multi = false
    }

    private fun refreshDigest() {
        val ctx = requireContext().applicationContext
        if (digesting || AnalysisState.isActive("digest:$digestKey")) {
            DigestWorker.cancel(ctx)
            digesting = false
            digestHandler.removeCallbacks(digestTicker)
            toast("已取消时段总结")
            apply()
            return
        }
        if (LlmEngine.modelIn(LocalAsr.llmDir(ctx)) == null && !LlmSettings.remoteEnabled(ctx)) {
            MaterialAlertDialogBuilder(requireContext()).setTitle("准备整理方式").setMessage("使用本地模型或配置在线服务后，可以生成时段总结。")
                .setPositiveButton("去设置") { _, _ -> startActivity(AiSettingsActivity.intent(requireContext())) }.setNegativeButton("取消", null).show()
            return
        }
        val r = range()
        val inRange = todos.filter { r == null || sourceDate(it).let { d -> !d.isBefore(r.first) && !d.isAfter(r.second) } }.filter { !it.optBoolean("done") }
        val recsInRange = recordings.filter { rec ->
            r == null || (Ui.localDate(rec.optString("occurredAt")) ?: LocalDate.now()).let { d -> !d.isBefore(r.first) && !d.isAfter(r.second) }
        }
        val todoLines = inRange.sortedBy { DueDates.sortKey(it.optString("due")) }.map { t ->
            val cat = when (LocalAnalysis.category(t.optString("category"))) { "work" -> "公事"; "personal" -> "私事"; else -> "未分类" }
            val due = t.optString("due").ifBlank { "无期限" }
            val state = if (DueDates.state(t.optString("due")) == DueDates.State.OVERDUE) "已逾期" else if (!t.optBoolean("reviewed")) "待核对" else ""
            "待办[$cat] ${t.optString("title")}（$due）$state".trim()
        }
        val recLines = recsInRange.filter { it.optString("summary").isNotBlank() }.map { rec ->
            "录音纪要《${rec.optString("title").ifBlank { "录音" }}》：${rec.optString("summary").replace('\n', ' ').trim().take(160)}"
        }
        val lines = todoLines + recLines
        if (lines.isEmpty()) { toast("这个时段还没有待推进的事项或已整理的录音纪要"); return }
        DigestWorker.clear(ctx, digestKey)
        val id = DigestWorker.enqueue(ctx, digestKey, rangeLabel(r), lines)
        digesting = true; apply()
        digestHandler.removeCallbacks(digestTicker)
        digestHandler.postDelayed(digestTicker, 1000)
        WorkManager.getInstance(ctx).getWorkInfoByIdLiveData(id).observe(viewLifecycleOwner) { info -> if (info != null && info.state.isFinished) { digesting = false; digestHandler.removeCallbacks(digestTicker); apply() } }
    }

    private fun swipeCallback() = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val paint = Paint()
        override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int = if (vh is TodoAdapter.ItemVH && !multi) super.getSwipeDirs(rv, vh) else 0
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val row = adapter.currentList.getOrNull(vh.bindingAdapterPosition) as? TodoAdapter.Row.Item ?: return
            val id = row.json.getString("id")
            if (direction == ItemTouchHelper.RIGHT) { mutate { LocalStore.of(it).setDone(id, !row.json.optBoolean("done")) }; toast(if (row.json.optBoolean("done")) "已恢复为未完成" else "已完成") }
            else { adapter.notifyItemChanged(vh.bindingAdapterPosition); postpone(listOf(id)) }
        }
        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
            val v = vh.itemView
            if (dX != 0f) {
                paint.color = ContextCompat.getColor(requireContext(), if (dX > 0) R.color.md_primary_container else R.color.md_tertiary_container)
                val r = v.resources.displayMetrics.density * 20
                val rect = if (dX > 0) android.graphics.RectF(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat()) else android.graphics.RectF(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat())
                c.drawRoundRect(rect, r, r, paint)
                paint.color = ContextCompat.getColor(requireContext(), if (dX > 0) R.color.md_on_primary_container else R.color.md_on_tertiary_container)
                paint.textSize = v.resources.displayMetrics.scaledDensity * 15
                val label = if (dX > 0) "完成" else "推迟"
                val x = if (dX > 0) v.left + r else v.right - r - paint.measureText(label)
                c.drawText(label, x, v.top + v.height / 2f + paint.textSize / 3, paint)
            }
            super.onChildDraw(c, rv, vh, dX, dY, state, active)
        }
    }
}

class TodoAdapter(
    private val onToggle: (String, Boolean) -> Unit,
    private val onOpen: (String) -> Unit,
    private val onLongPress: (String) -> Unit,
    private val onSource: (String) -> Unit,
    private val onCategory: (String, String) -> Unit,
    private val onSummaryRefresh: () -> Unit,
    private val onOpenTrace: () -> Unit,
    private val onCategoryFilter: (String) -> Unit,
    private val onStatusFilter: (Int) -> Unit,
    private val isSelected: (String) -> Boolean,
    private val multiMode: () -> Boolean,
) : ListAdapter<TodoAdapter.Row, RecyclerView.ViewHolder>(DIFF) {
    data class Summary(val title: String, val counts: String, val overview: String, val error: String, val source: String,
                       val first: List<String>, val category: String, val status: Int, val busy: Boolean = false)
    sealed class Row {
        abstract val key: String
        abstract val signature: String
        data class Header2(val summary: Summary) : Row() { override val key = "summary"; override val signature = summary.toString() }
        data class Empty(val title: String, val body: String) : Row() { override val key = "empty"; override val signature = title + body }
        data class Header(val title: String, val count: Int) : Row() { override val key = "h:$title"; override val signature = "$title:$count" }
        data class Item(val json: JSONObject) : Row() { override val key = json.getString("id"); override val signature = json.toString() }
    }
    class SummaryVH(val b: ItemTodoSummaryBinding) : RecyclerView.ViewHolder(b.root)
    class EmptyVH(val b: com.bob.recordinghelper.databinding.ViewEmptyBinding) : RecyclerView.ViewHolder(b.root)
    class HeaderVH(val b: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class ItemVH(val b: ItemTodoBinding) : RecyclerView.ViewHolder(b.root)
    private var selectionStamp = 0
    fun invalidateSelection() { selectionStamp++; notifyDataSetChanged() }

    override fun getItemViewType(position: Int) = when (getItem(position)) { is Row.Header2 -> 2; is Row.Empty -> 3; is Row.Header -> 0; else -> 1 }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            2 -> SummaryVH(ItemTodoSummaryBinding.inflate(inflater, parent, false))
            3 -> EmptyVH(com.bob.recordinghelper.databinding.ViewEmptyBinding.inflate(inflater, parent, false))
            0 -> HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            else -> ItemVH(ItemTodoBinding.inflate(inflater, parent, false))
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header2 -> bindSummary((holder as SummaryVH).b, row.summary)
            is Row.Empty -> (holder as EmptyVH).b.let { it.icon.setImageResource(R.drawable.ic_tasks); it.tvTitle.text = row.title; it.tvBody.text = row.body }
            is Row.Header -> (holder as HeaderVH).b.let { it.tvTitle.text = row.title; it.tvCount.text = "${row.count}" }
            is Row.Item -> {
                val id = row.json.getString("id")
                val source = row.json.optString("recordingId")
                val vb = (holder as ItemVH).b
                Binders.todo(vb, row.json, { checked -> if (multiMode()) onOpen(id) else onToggle(id, checked) }, { onOpen(id) },
                    if (source.isBlank()) null else ({ onSource(source) }), { next -> onCategory(id, next) })
                vb.card.setOnLongClickListener { onLongPress(id); true }
                val sel = multiMode() && isSelected(id)
                vb.card.isChecked = sel
                val d = vb.root.resources.displayMetrics.density
                vb.card.strokeWidth = (if (sel) d * 2f else d).toInt().coerceAtLeast(1)
                vb.card.strokeColor = ContextCompat.getColor(vb.root.context, if (sel) R.color.md_primary else R.color.glass_card_stroke)
            }
        }
    }
    private fun bindSummary(b: ItemTodoSummaryBinding, s: Summary) {
        b.tvTitle.text = s.title
        b.tvCounts.text = s.counts
        val has = s.overview.isNotBlank()
        b.tvOverview.visibility = View.VISIBLE
        b.tvOverview.text = when {
            has -> s.overview
            s.error.isNotBlank() -> "无法生成总结：${s.error}"
            else -> "点击右上角「一键总结」，由模型汇总本时段的录音纪要与待办重点。"
        }
        b.tvOverview.setOnClickListener { if (s.busy || has) onOpenTrace() else onSummaryRefresh() }
        b.tvSource.visibility = if (s.source.isNotBlank()) View.VISIBLE else View.GONE
        b.tvSource.text = s.source
        b.tvSource.setOnClickListener { onOpenTrace() }
        b.tvFirstTitle.visibility = if (s.first.isNotEmpty()) View.VISIBLE else View.GONE
        b.tvFirst.visibility = b.tvFirstTitle.visibility
        b.tvFirst.text = s.first.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        b.btnRefresh.text = if (s.busy) "取消总结" else if (has) "一键重新总结" else "一键总结"
        b.btnRefresh.isEnabled = true
        b.btnRefresh.setOnClickListener { onSummaryRefresh() }
        b.categoryFilters.setOnCheckedStateChangeListener(null)
        b.categoryFilters.check(when (s.category) { "work" -> R.id.catWork; "personal" -> R.id.catPersonal; "none" -> R.id.catNone; else -> R.id.catAll })
        b.categoryFilters.setOnCheckedStateChangeListener { _, ids -> onCategoryFilter(when (ids.firstOrNull()) { R.id.catWork -> "work"; R.id.catPersonal -> "personal"; R.id.catNone -> "none"; else -> "all" }) }
        b.statusFilters.setOnCheckedStateChangeListener(null)
        b.statusFilters.check(s.status)
        b.statusFilters.setOnCheckedStateChangeListener { _, ids -> ids.firstOrNull()?.let { onStatusFilter(it) } }
    }
    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a.signature == b.signature
        }
    }
}
