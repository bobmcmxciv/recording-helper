package com.bob.recordinghelper.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bob.recordinghelper.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A bottom-navigation tab: reloads whenever it becomes visible. */
abstract class TabFragment : Fragment() {
    override fun onResume() { super.onResume(); if (!isHidden) refresh() }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden && view != null) refresh() }
    abstract fun refresh()

    protected val main: MainActivity? get() = activity as? MainActivity

    /** Run [work] off the main thread, then [show] the result on it (skipped if the view is gone). */
    protected fun <T> load(work: () -> T, show: (T) -> Unit) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(work) }
            if (view == null) return@launch
            result.onSuccess(show).onFailure { Ui.toast(requireContext(), it.message ?: "读取失败") }
        }
    }

    protected fun toast(message: String) { context?.let { Ui.toast(it, message) } }
}
