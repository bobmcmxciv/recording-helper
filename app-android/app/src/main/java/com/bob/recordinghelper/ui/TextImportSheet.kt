package com.bob.recordinghelper.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.bob.recordinghelper.databinding.SheetTextImportBinding
import com.bob.recordinghelper.local.LocalImport
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pasted text goes through the same local analysis pipeline as a transcript. */
class TextImportSheet : BottomSheetDialogFragment() {
    private var _b: SheetTextImportBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = SheetTextImportBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext().applicationContext
        b.btnSave.setOnClickListener {
            val text = b.text.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) { b.text.error = "请粘贴内容"; return@setOnClickListener }
            val title = b.title.text?.toString()?.trim().orEmpty().ifEmpty { "文字记录" }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { LocalImport.text(ctx, title, text) } }
                Ui.toast(ctx, if (result.isSuccess) "已保存在本机，稍后生成纪要" else "保存失败：${result.exceptionOrNull()?.message}")
                parentFragmentManager.setFragmentResult("changed", bundleOf())
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }
}
