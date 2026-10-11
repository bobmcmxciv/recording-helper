package com.bob.recordinghelper.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.bob.recordinghelper.databinding.SheetTodoEditBinding
import com.bob.recordinghelper.local.LocalStore
import com.bob.recordinghelper.local.SyncWorker
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/** Create or edit one todo. Reports "changed" to the parent fragment manager when something was saved. */
class TodoEditSheet : BottomSheetDialogFragment() {
    private var _b: SheetTodoEditBinding? = null
    private val b get() = _b!!
    private val id: String? get() = arguments?.getString("id")
    private var recordingId = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = SheetTodoEditBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext().applicationContext
        val editing = id != null
        b.tvHeading.text = if (editing) "编辑待办" else "新建待办"
        b.btnDelete.visibility = if (editing) View.VISIBLE else View.GONE
        b.btnSource.visibility = View.GONE
        if (editing && savedInstanceState == null) lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) { LocalStore.of(ctx).get(id!!) } ?: return@launch
            fill(t)
        } else if (editing) lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) { LocalStore.of(ctx).get(id!!) } ?: return@launch
            recordingId = t.optString("recordingId"); b.btnSource.visibility = if (recordingId.isBlank()) View.GONE else View.VISIBLE
        }
        b.dueLayout.setEndIconOnClickListener {
            MaterialDatePicker.Builder.datePicker().setTitleText("选择期限").build().apply {
                addOnPositiveButtonClickListener { ms -> b.due.setText(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
            }.show(parentFragmentManager, "due-date")
        }
        b.btnSave.setOnClickListener {
            val title = b.title.text?.toString()?.trim().orEmpty()
            if (title.isEmpty()) { b.title.error = "请输入内容"; return@setOnClickListener }
            val due = b.due.text?.toString()?.trim().orEmpty()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val store = LocalStore.of(ctx)
                    if (id == null) store.addTodo(title, due) else store.updateTodo(id!!, title, due)
                    SyncWorker.schedule(ctx)
                }
                done()
            }
        }
        b.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("删除这条待办？").setMessage("同步工作空间中的副本也会被标记为删除。")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch { withContext(Dispatchers.IO) { LocalStore.of(ctx).delete(id!!); SyncWorker.schedule(ctx) }; done() }
                }.setNegativeButton("取消", null).show()
        }
        b.btnSource.setOnClickListener {
            if (recordingId.isNotBlank()) startActivity(RecordingDetailActivity.intent(requireContext(), recordingId))
            dismiss()
        }
    }

    private fun fill(t: JSONObject) {
        b.title.setText(t.optString("title"))
        b.due.setText(t.optString("due"))
        val evidence = t.optString("evidence").trim()
        b.tvEvidence.visibility = if (evidence.isBlank()) View.GONE else View.VISIBLE
        b.tvEvidence.text = "依据：$evidence"
        recordingId = t.optString("recordingId")
        b.btnSource.visibility = if (recordingId.isBlank()) View.GONE else View.VISIBLE
    }

    private fun done() {
        parentFragmentManager.setFragmentResult("changed", bundleOf())
        dismissAllowingStateLoss()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    companion object {
        fun newInstance(id: String?) = TodoEditSheet().apply { arguments = bundleOf("id" to id) }
    }
}
