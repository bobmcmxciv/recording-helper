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
    private var recordingDates: Map<String, LocalDate> = emptyMap()
    private var customRange: kotlin.Pair<LocalDate, LocalDate>? = null
    private var category = "all"
    private var status = R.id.filterOpen
    private var multi = false
    private val selected = LinkedHashSet<String>()
    private var digestKey = ""
    private var digesting = false

    private val adapter = TodoAdapter(
        onToggle = { id, checked -> mutate { LocalStore.of(it).setDone(id, checked) } },
        onOpen = { id -> if (multi) toggleSelect(id) else TodoEditSheet.newInstance(id).show(childFragmentManager, "todo") },
        onLongPress = { id -> if (!multi) { multi = true; selected.clear() }; toggleSelect(id) },
        onSource = { recordingId -> startActivity(RecordingDetailActivity.intent(requireContext(), recordingId)) },
        onCategory = { id, next -> mutate { LocalStore.of(it).setCategory(id, next) } },
        onSummaryRefresh = { refreshDigest() },
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
        val wide = Ui.width(requireContext()) == Ui.Width.EXPANDED
        b.list.layoutManager = if (wide) androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2).apply {
            spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == 1) 1 else 2 }
        } else LinearLayoutManager(requireContext())
        Ui.column(b.root, if (wide) 1080 else 720)
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

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    override fun refresh() {
        if (_b == null) return
        val ctx = requireContext().applicationContext
        load({
            val store = LocalStore.of(ctx)
            val dates = store.all("recording").associate { it.getString("id") to (Ui.localDate(it.optString("occurredAt")) ?: LocalDate.now()) }
            store.all("todo") to dates
        }) { (rows, dates) -> todos = rows; recordingDates = dates; apply() }
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
        val summary = TodoAdapter.Summary(
            title = "${rangeLabel(r)} ${open.size} 件事",
            counts = "公事 ${byCat("work")} · 私事 ${byCat("personal")} · 未分类 ${byCat("")}" + if (overdue > 0) " · 逾期 $overdue" else "",
            overview = if (digesting) "正在整理这个时段的重点…本地模型可能需要一两分钟。" else digest?.optString("overview").orEmpty(),
            error = if (digesting) "" else digest?.optString("error").orEmpty(), source = if (digesting) "" else digest?.optString("source").orEmpty(),
            first = if (digesting) emptyList() else digest?.optJSONArray("first")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            category = category, status = status, busy = digesting,
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
        if (LlmEngine.modelIn(LocalAsr.llmDir(ctx)) == null && !LlmSettings.remoteEnabled(ctx)) {
            MaterialAlertDialogBuilder(requireContext()).setTitle("准备整理方式").setMessage("使用本地模型或配置在线服务后，可以生成时段总结。")
                .setPositiveButton("去设置") { _, _ -> startActivity(AiSettingsActivity.intent(requireContext())) }.setNegativeButton("取消", null).show()
            return
        }
        val r = range()
        val inRange = todos.filter { r == null || sourceDate(it).let { d -> !d.isBefore(r.first) && !d.isAfter(r.second) } }.filter { !it.optBoolean("done") }
        if (inRange.isEmpty()) { toast("这个时段没有待推进的事"); return }
        val lines = inRange.sortedBy { DueDates.sortKey(it.optString("due")) }.map { t ->
            val cat = when (LocalAnalysis.category(t.optString("category"))) { "work" -> "公事"; "personal" -> "私事"; else -> "未分类" }
            val due = t.optString("due").ifBlank { "无期限" }
            val state = if (DueDates.state(t.optString("due")) == DueDates.State.OVERDUE) "已逾期" else if (!t.optBoolean("reviewed")) "待核对" else ""
            "[$cat] ${t.optString("title")}（$due）$state".trim()
        }
        DigestWorker.clear(ctx, digestKey)
        val id = DigestWorker.enqueue(ctx, digestKey, rangeLabel(r), lines)
        digesting = true; apply()
        WorkManager.getInstance(ctx).getWorkInfoByIdLiveData(id).observe(viewLifecycleOwner) { info -> if (info != null && info.state.isFinished) { digesting = false; apply() } }
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
                vb.card.strokeWidth = if (sel) (vb.root.resources.displayMetrics.density * 2).toInt() else 0
                vb.card.strokeColor = ContextCompat.getColor(vb.root.context, R.color.md_primary)
            }
        }
    }
    private fun bindSummary(b: ItemTodoSummaryBinding, s: Summary) {
        b.tvTitle.text = s.title
        b.tvCounts.text = s.counts
        val has = s.overview.isNotBlank()
        b.tvOverview.visibility = if (has || s.error.isNotBlank()) View.VISIBLE else View.GONE
        b.tvOverview.text = if (has) s.overview else "无法生成总结：${s.error}"
        b.tvSource.visibility = if (has && s.source.isNotBlank()) View.VISIBLE else View.GONE
        b.tvSource.text = s.source + " · 按来源录音时间统计"
        b.tvFirstTitle.visibility = if (s.first.isNotEmpty()) View.VISIBLE else View.GONE
        b.tvFirst.visibility = b.tvFirstTitle.visibility
        b.tvFirst.text = s.first.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        b.btnRefresh.text = if (s.busy) "整理中" else if (has) "刷新" else "总结"
        b.btnRefresh.isEnabled = !s.busy
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
