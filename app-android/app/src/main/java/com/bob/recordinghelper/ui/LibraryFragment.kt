package com.bob.recordinghelper.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bob.recordinghelper.R
import com.bob.recordinghelper.databinding.FragmentLibraryBinding
import com.bob.recordinghelper.databinding.ItemRecordingBinding
import com.bob.recordinghelper.local.AnalysisState
import com.bob.recordinghelper.local.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LibraryFragment : TabFragment() {
    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!
    private var rows: List<JSONObject> = emptyList()
    private var todoCounts: Map<String, Int> = emptyMap()
    private val adapter = RecordingAdapter { id -> startActivity(RecordingDetailActivity.intent(requireContext(), id)) }

    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch { val message = withContext(Dispatchers.IO) { Imports.audio(ctx, uris) }; toast(message); refresh() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val columns = if (Ui.sidePane(b.root)) 1 else Ui.gridColumns(requireContext())
        b.list.layoutManager = if (columns > 1) androidx.recyclerview.widget.GridLayoutManager(requireContext(), columns) else LinearLayoutManager(requireContext())
        Ui.column(b.root, if (Ui.sidePane(b.root)) 1440 else Ui.gridMaxDp(requireContext()))
        b.list.adapter = adapter
        b.list.itemAnimator = null
        b.swipe.setOnRefreshListener { refresh() }
        b.fab.setOnClickListener { importAudio.launch(Imports.AUDIO_TYPES) }
        b.search.doAfterTextChanged { apply() }
        b.filters.setOnCheckedStateChangeListener { _, _ -> apply() }
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { if (dy > 8) b.fab.shrink() else if (dy < -8) b.fab.extend() }
        })
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveTicker = object : Runnable {
        override fun run() {
            if (_b != null && AnalysisState.snapshot().running) {
                apply()
                handler.postDelayed(this, 1000)
            }
        }
    }
    private val analysisListener = {
        refresh()
        if (AnalysisState.snapshot().running) {
            handler.removeCallbacks(liveTicker)
            handler.postDelayed(liveTicker, 1000)
        }
    }
    override fun onResume() {
        super.onResume()
        AnalysisState.addListener(analysisListener)
        if (AnalysisState.snapshot().running) {
            handler.removeCallbacks(liveTicker)
            handler.postDelayed(liveTicker, 1000)
        }
    }
    override fun onPause() {
        handler.removeCallbacks(liveTicker)
        AnalysisState.removeListener(analysisListener)
        super.onPause()
    }
    override fun onDestroyView() { _b = null; super.onDestroyView() }

    override fun refresh() {
        if (_b == null) return
        val ctx = requireContext().applicationContext
        load({
            val store = LocalStore.of(ctx)
            val recordings = store.all("recording").sortedByDescending { it.optString("occurredAt") }
            recordings to store.all("todo").groupingBy { it.optString("recordingId") }.eachCount()
        }) { (recordings, counts) ->
            rows = recordings; todoCounts = counts
            b.swipe.isRefreshing = false
            apply()
        }
    }

    private fun apply() {
        if (_b == null) return
        val status = when (b.filters.checkedChipId) {
            R.id.filterPending -> setOf("pending", "analyzing")
            R.id.filterReady -> setOf("ready")
            R.id.filterError -> setOf("error")
            R.id.filterSilent -> setOf("silent")
            else -> null
        }
        val q = b.search.text?.toString()?.trim().orEmpty()
        val filtered = rows.filter { r -> status == null || r.optString("status") in status }
            .filter { r -> q.isEmpty() || r.optString("title").contains(q, true) || r.optString("text").contains(q, true) || r.optString("summary").contains(q, true) }
        adapter.submitList(filtered.map { RecordingAdapter.Row(it.getString("id"), it, todoCounts[it.getString("id")] ?: 0) })
        b.tvCount.text = if (filtered.size == rows.size) "${rows.size} 份记录" else "${filtered.size} / ${rows.size} 份"
        val empty = filtered.isEmpty()
        b.empty.root.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            val searching = q.isNotEmpty() || status != null
            b.empty.tvTitle.text = if (searching) "没有匹配的录音" else getString(R.string.empty_recordings_title)
            b.empty.tvBody.text = if (searching) "换个关键词或筛选条件试试。" else getString(R.string.empty_recordings_body)
        }
    }
}

class RecordingAdapter(private val onOpen: (String) -> Unit) : ListAdapter<RecordingAdapter.Row, RecordingAdapter.VH>(DIFF) {
    data class Row(val id: String, val json: JSONObject, val todoCount: Int) {
        private val snap = AnalysisState.snapshot()
        private val active = snap.activeId == id
        private val elapsedBucket = if (active && snap.startedAt > 0L) (System.currentTimeMillis() - snap.startedAt) / 1000L else 0L
        val signature: String = "${json}|$todoCount|$active|${id in snap.queuedIds}|${if (active) snap.phase else ""}|${if (active) snap.streamText.takeLast(80) else ""}|$elapsedBucket"
    }
    class VH(val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        Binders.recording(holder.b, row.json, row.todoCount) { onOpen(row.id) }
    }
    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
            override fun areContentsTheSame(a: Row, b: Row) = a.signature == b.signature
        }
    }
}
