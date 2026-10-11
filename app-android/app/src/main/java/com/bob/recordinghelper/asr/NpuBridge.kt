package com.bob.recordinghelper.asr

/** MediaTek NPU exposed as an NNAPI support library: libnnapi_neuron_shim.so → the phone's own libneuronusdk_adapter.9.mtk.so. */
object NpuBridge {
    /** null when the native libraries loaded, otherwise the load error. */
    val loadError: String? = try {
        System.loadLibrary("nnapi_neuron_shim")
        null
    } catch (t: Throwable) {
        t.toString()
    }

    /** Pointer for NnApiDelegate.Options.setNnApiSupportLibraryHandle(). */
    @JvmStatic external fun supportLibraryHandle(): Long

    /** NPU devices visible to the Neuron adapter, comma separated. */
    @JvmStatic external fun deviceNames(): String

    /** Neuron runtime version, "major.minor.patch". */
    @JvmStatic external fun version(): String

    /** Log every model / compilation step to logcat (tag neuron-shim). */
    @JvmStatic external fun setVerbose(on: Boolean)

    /** Ignore compilation caching requests (diagnostics). */
    @JvmStatic external fun setNoCache(on: Boolean)

    /** Extra Neuron compiler options for following compilations, e.g. "--num-mdla=2"; "" for the defaults. */
    @JvmStatic external fun setCompileOptions(options: String)
}
