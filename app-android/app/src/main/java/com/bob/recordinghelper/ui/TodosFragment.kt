package com.bob.recordinghelper.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bob.recordinghelper.R
import com.bob.recordinghelper.databinding.FragmentTodosBinding
import com.bob.recordinghelper.databinding.ItemSectionHeaderBinding
import com.bob.recordinghelper.databinding.ItemTodoBinding
import com.bob.recordinghelper.local.DueDates
import com.bob.recordinghelper.local.LocalStore
import com.bob.recordinghelper.local.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TodosFragment : TabFragment() {
    private var _b: FragmentTodosBinding? = null
    private val b get() = _b!!
    private var todos: List<JSONObject> = emptyList()
    private val adapter = TodoAdapter(
        onToggle = { id, checked -> background { LocalStore.of(requireContext().applicationContext).setDone(id, checked); SyncWorker.schedule(requireContext().applicationContext) } },
        onOpen = { id -> TodoEditSheet.newInstance(id).show(childFragmentManager, "todo") },
        onSource = { recordingId -> startActivity(RecordingDetailActivity.intent(requireContext(), recordingId)) },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentTodosBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.fab.setOnClickListener { TodoEditSheet.newInstance(null).show(childFragmentManager, "todo") }
        b.filters.setOnCheckedStateChangeListener { _, _ -> apply() }
        childFragmentManager.setFragmentResultListener("changed", viewLifecycleOwner) { _, _ -> refresh() }
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { if (dy > 8) b.fab.shrink() else if (dy < -8) b.fab.extend() }
        })
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    override fun refresh() {
        if (_b == null) return
        val ctx = requireContext().applicationContext
        load({ LocalStore.of(ctx).all("todo") }) { todos = it; apply() }
    }

    private fun apply() {
        if (_b == null) return
        val open = todos.filter { !it.optBoolean("done") }
        val today = open.filter { DueDates.state(it.optString("due")).let { s -> s == DueDates.State.OVERDUE || s == DueDates.State.TODAY } }
        b.tvSummary.text = "${open.size} 项未完成  ·  今日必办 ${today.size}  ·  待核对 ${open.count { !it.optBoolean("reviewed") }}"
        val byDue = compareBy<JSONObject> { DueDates.sortKey(it.optString("due")) }.thenByDescending { it.optLong("createdAt") }
        val rows = ArrayList<TodoAdapter.Row>()
        fun section(title: String, items: List<JSONObject>) {
            if (items.isEmpty()) return
            rows.add(TodoAdapter.Row.Header(title, items.size))
            items.forEach { rows.add(TodoAdapter.Row.Item(it)) }
        }
        when (b.filters.checkedChipId) {
            R.id.filterReview -> section("待核对的 AI 建议", open.filter { !it.optBoolean("reviewed") }.sortedWith(byDue))
            R.id.filterDone -> section("已完成", todos.filter { it.optBoolean("done") }.sortedByDescending { it.optLong("createdAt") }.take(200))
            else -> {
                section("今日必办", today.sortedWith(byDue))
                section("待核对", open.filter { !it.optBoolean("reviewed") && it !in today }.sortedWith(byDue))
                section("其他未完成", open.filter { it.optBoolean("reviewed") && it !in today }.sortedWith(byDue))
            }
        }
        adapter.submitList(rows)
        b.empty.root.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.empty.icon.setImageResource(R.drawable.ic_tasks)
        b.empty.tvTitle.text = if (b.filters.checkedChipId == R.id.filterDone) "还没有完成的待办" else getString(R.string.empty_todos_title)
        b.empty.tvBody.text = getString(R.string.empty_todos_body)
    }

    private fun background(work: () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching(work) }; refresh() }
    }
}

class TodoAdapter(
    private val onToggle: (String, Boolean) -> Unit,
    private val onOpen: (String) -> Unit,
    private val onSource: (String) -> Unit,
) : ListAdapter<TodoAdapter.Row, RecyclerView.ViewHolder>(DIFF) {
    sealed class Row {
        abstract val key: String
        abstract val signature: String
        data class Header(val title: String, val count: Int) : Row() {
            override val key = "h:$title"; override val signature = "$title:$count"
        }
        data class Item(val json: JSONObject) : Row() {
            override val key = json.getString("id"); override val signature = json.toString()
        }
    }
    class HeaderVH(val b: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class ItemVH(val b: ItemTodoBinding) : RecyclerView.ViewHolder(b.root)
    override fun getItemViewType(position: Int) = if (getItem(position) is Row.Header) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false)) else ItemVH(ItemTodoBinding.inflate(inflater, parent, false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderVH).b.let { it.tvTitle.text = row.title; it.tvCount.text = "${row.count}" }
            is Row.Item -> {
                val id = row.json.getString("id")
                val source = row.json.optString("recordingId")
                Binders.todo((holder as ItemVH).b, row.json, { checked -> onToggle(id, checked) }, { onOpen(id) },
                    if (source.isBlank()) null else ({ onSource(source) }))
            }
        }
    }
    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a.signature == b.signature
        }
    }
}
