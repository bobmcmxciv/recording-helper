// NNAPI Support Library (feature level 5+) implemented on top of MediaTek's public Neuron USDK adapter
// (libneuronusdk_adapter.so). Lets the stock TFLite NNAPI delegate reach the MediaTek NPU on phones whose
// system no longer ships NNAPI (or from processes that cannot see /vendor libraries).
//
// The Neuron Adapter API mirrors NNAPI: same operation codes, operand types and call sequence. Differences
// handled here: result codes are numbered differently after 5, there is no setOperandValueFromMemory (we map
// the fd ourselves and pass pointers), and devices carry no type / version / feature level.
#include <android/log.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "tensorflow/lite/nnapi/sl/public/NeuralNetworksSupportLibraryImpl.h"

extern "C" {
#include "neuron/api/NeuronAdapter.h"
}

namespace {

int64_t FeatureLevel() {
    const char* e = getenv("SHIM_FEATURE_LEVEL");
    return e ? atoll(e) : ANEURALNETWORKS_FEATURE_LEVEL_7;
}

std::atomic<bool> g_verbose{false};  // NpuBridge.setVerbose()
std::atomic<bool> g_no_cache{false}; // NpuBridge.setNoCache(): ignore compilation caching requests
std::string g_compile_options;       // NpuBridge.setCompileOptions(); guarded by g_mu

bool Verbose() { return g_verbose.load() || getenv("SHIM_VERBOSE") != nullptr; }

// logcat (tag neuron-shim) always; stderr as well for command-line runs with SHIM_VERBOSE.
void Alog(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, "neuron-shim", fmt, ap);
    va_end(ap);
    if (getenv("SHIM_VERBOSE")) {
        va_start(ap, fmt);
        vfprintf(stderr, fmt, ap);
        fputc('\n', stderr);
        fflush(stderr);
        va_end(ap);
    }
}

std::mutex g_mu;
std::map<const void*, std::vector<int>> g_ops;  // model -> op types in insertion order (for diagnostics)

void Trace(const char* what) {
    if (Verbose()) Alog("%s", what);
}


int R(int n) {
    switch (n) {
        case NEURON_NO_ERROR: return ANEURALNETWORKS_NO_ERROR;
        case NEURON_OUT_OF_MEMORY: return ANEURALNETWORKS_OUT_OF_MEMORY;
        case NEURON_INCOMPLETE: return ANEURALNETWORKS_INCOMPLETE;
        case NEURON_UNEXPECTED_NULL: return ANEURALNETWORKS_UNEXPECTED_NULL;
        case NEURON_BAD_DATA: return ANEURALNETWORKS_BAD_DATA;
        case NEURON_OP_FAILED: return ANEURALNETWORKS_OP_FAILED;
        case NEURON_UNMAPPABLE: return ANEURALNETWORKS_UNMAPPABLE;
        case NEURON_BAD_STATE: return ANEURALNETWORKS_BAD_STATE;
        case NEURON_OUTPUT_INSUFFICIENT_SIZE: return ANEURALNETWORKS_OUTPUT_INSUFFICIENT_SIZE;
        case NEURON_UNAVAILABLE_DEVICE: return ANEURALNETWORKS_UNAVAILABLE_DEVICE;
        case NEURON_MISSED_DEADLINE_TRANSIENT: return ANEURALNETWORKS_MISSED_DEADLINE_TRANSIENT;
        case NEURON_MISSED_DEADLINE_PERSISTENT: return ANEURALNETWORKS_MISSED_DEADLINE_PERSISTENT;
        default: return ANEURALNETWORKS_OP_FAILED;
    }
}

int Log(const char* what, int rc) {
    if (rc != NEURON_NO_ERROR) Alog("%s -> neuron rc=%d", what, rc);
    return R(rc);
}

// Our own memory object: an mmap of the caller's fd. Constants and I/O are passed to Neuron as pointers into it.
struct ShimMemory {
    void* base;
    size_t size;
};

inline NeuronModel* M(ANeuralNetworksModel* m) { return reinterpret_cast<NeuronModel*>(m); }
inline NeuronCompilation* C(ANeuralNetworksCompilation* c) { return reinterpret_cast<NeuronCompilation*>(c); }
inline NeuronExecution* E(ANeuralNetworksExecution* e) { return reinterpret_cast<NeuronExecution*>(e); }
inline const NeuronDevice* D(const ANeuralNetworksDevice* d) { return reinterpret_cast<const NeuronDevice*>(d); }
inline const NeuronOperandType* T(const ANeuralNetworksOperandType* t) {
    static_assert(sizeof(ANeuralNetworksOperandType) == sizeof(NeuronOperandType), "operand type layout");
    return reinterpret_cast<const NeuronOperandType*>(t);
}

// ---- devices ----
int GetDeviceCount(uint32_t* n) { return Log("getDeviceCount", Neuron_getDeviceCount(n)); }
int GetDevice(uint32_t i, ANeuralNetworksDevice** d) {
    return Log("getDevice", Neuron_getDevice(i, reinterpret_cast<NeuronDevice**>(d)));
}
int DeviceGetName(const ANeuralNetworksDevice* d, const char** name) { return Log("getName", NeuronDevice_getName(D(d), name)); }
int DeviceGetType(const ANeuralNetworksDevice* d, int32_t* type) {
    const char* name = "";
    NeuronDevice_getName(D(d), &name);
    *type = (name && strstr(name, "gpu")) ? ANEURALNETWORKS_DEVICE_GPU
          : (name && strstr(name, "cpu")) ? ANEURALNETWORKS_DEVICE_CPU
          : ANEURALNETWORKS_DEVICE_ACCELERATOR;
    return ANEURALNETWORKS_NO_ERROR;
}
int DeviceGetVersion(const ANeuralNetworksDevice*, const char** version) {
    *version = "neuron-usdk-shim";
    return ANEURALNETWORKS_NO_ERROR;
}
int DeviceGetFeatureLevel(const ANeuralNetworksDevice*, int64_t* level) {
    *level = FeatureLevel();
    return ANEURALNETWORKS_NO_ERROR;
}
int DeviceWait(const ANeuralNetworksDevice*) { return ANEURALNETWORKS_NO_ERROR; }
int DeviceGetExtensionSupport(const ANeuralNetworksDevice*, const char*, bool* supported) {
    *supported = false;
    return ANEURALNETWORKS_NO_ERROR;
}
int64_t RuntimeFeatureLevel() { return FeatureLevel(); }
uint64_t DefaultLoopTimeout() { return 2000000000ull; }
uint64_t MaximumLoopTimeout() { return 15000000000ull; }

// ---- memory ----
int MemoryCreateFromFd(size_t size, int protect, int fd, size_t offset, ANeuralNetworksMemory** memory) {
    void* p = mmap(nullptr, size, protect, MAP_SHARED, fd, static_cast<off_t>(offset));
    if (p == MAP_FAILED) return ANEURALNETWORKS_UNMAPPABLE;
    *memory = reinterpret_cast<ANeuralNetworksMemory*>(new ShimMemory{p, size});
    return ANEURALNETWORKS_NO_ERROR;
}
void MemoryFree(ANeuralNetworksMemory* memory) {
    auto* m = reinterpret_cast<ShimMemory*>(memory);
    if (!m) return;
    munmap(m->base, m->size);
    delete m;
}
inline char* At(const ANeuralNetworksMemory* memory, size_t offset) {
    return static_cast<char*>(reinterpret_cast<const ShimMemory*>(memory)->base) + offset;
}

// ---- model ----
int ModelCreate(ANeuralNetworksModel** m) { return Log("model_create", NeuronModel_create(reinterpret_cast<NeuronModel**>(m))); }
void ModelFree(ANeuralNetworksModel* m) {
    { std::lock_guard<std::mutex> l(g_mu); g_ops.erase(m); }
    NeuronModel_free(M(m));
}
int ModelFinish(ANeuralNetworksModel* m) { return Log("model_finish", NeuronModel_finish(M(m))); }
int ModelAddOperand(ANeuralNetworksModel* m, const ANeuralNetworksOperandType* t) {
    return Log("addOperand", NeuronModel_addOperand(M(m), T(t)));
}
int ModelSetOperandValue(ANeuralNetworksModel* m, int32_t i, const void* buf, size_t len) {
    return Log("setOperandValue", NeuronModel_setOperandValue(M(m), i, buf, len));
}
int ModelSetOperandValueFromMemory(ANeuralNetworksModel* m, int32_t i, const ANeuralNetworksMemory* mem, size_t off, size_t len) {
    return Log("setOperandValueFromMemory", NeuronModel_setOperandValue(M(m), i, At(mem, off), len));
}
int ModelSetSymmPerChannel(ANeuralNetworksModel* m, int32_t i, const ANeuralNetworksSymmPerChannelQuantParams* p) {
    return Log("setSymmPerChannel", NeuronModel_setOperandSymmPerChannelQuantParams(
        M(m), i, reinterpret_cast<const NeuronSymmPerChannelQuantParams*>(p)));
}
int ModelAddOperation(ANeuralNetworksModel* m, ANeuralNetworksOperationType type, uint32_t ni, const uint32_t* in,
                      uint32_t no, const uint32_t* out) {
    { std::lock_guard<std::mutex> l(g_mu); g_ops[m].push_back(type); }
    int rc = NeuronModel_addOperation(M(m), static_cast<NeuronOperationType>(type), ni, in, no, out);
    if (rc != NEURON_NO_ERROR) Alog("addOperation type=%d rc=%d", type, rc);
    return R(rc);
}
int ModelIdentifyIO(ANeuralNetworksModel* m, uint32_t ni, const uint32_t* in, uint32_t no, const uint32_t* out) {
    return Log("identifyInputsAndOutputs", NeuronModel_identifyInputsAndOutputs(M(m), ni, in, no, out));
}
int ModelRelaxFp16(ANeuralNetworksModel* m, bool allow) {
    return Log("relaxFp16", NeuronModel_relaxComputationFloat32toFloat16(M(m), allow));
}
int ModelGetSupportedOps(const ANeuralNetworksModel* m, const ANeuralNetworksDevice* const* devs, uint32_t n, bool* ops) {
    int rc = NeuronModel_getSupportedOperationsForDevices(
        reinterpret_cast<const NeuronModel*>(m), reinterpret_cast<const NeuronDevice* const*>(devs), n, ops);
    if (Verbose() && rc == NEURON_NO_ERROR) {
        std::lock_guard<std::mutex> l(g_mu);
        auto it = g_ops.find(m);
        if (it != g_ops.end()) {
            std::map<int, int> bad, all;
            for (size_t i = 0; i < it->second.size(); i++) { all[it->second[i]]++; if (!ops[i]) bad[it->second[i]]++; }
            for (auto& kv : all) Alog("model op type %d x%d", kv.first, kv.second);
            for (auto& kv : bad) Alog("unsupported op type %d x%d", kv.first, kv.second);
        }
    }
    return Log("getSupportedOperationsForDevices", rc);
}

// ---- compilation ----
int CompilationCreateForDevices(ANeuralNetworksModel* m, const ANeuralNetworksDevice* const* devs, uint32_t n,
                                ANeuralNetworksCompilation** c) {
    Alog("compilation createForDevices n=%u", n);
    return Log("createForDevices", NeuronCompilation_createForDevices(
        M(m), reinterpret_cast<const NeuronDevice* const*>(devs), n, reinterpret_cast<NeuronCompilation**>(c)));
}
int CompilationFinish(ANeuralNetworksCompilation* c) {
    std::string opts;
    { std::lock_guard<std::mutex> l(g_mu); opts = g_compile_options; }
    if (!opts.empty()) {
        // Measured on Dimensity 9500 / 9400: "--num-mdla=2" splits the work over both MDLA cores (92 -> 82 ms and
        // 122 -> 97 ms per 10 s segment, outputs identical). The default string is only "adapter --relax-fp32".
        int rc = NeuronCompilation_setOptimizationString(C(c), opts.c_str());
        Alog("compilation setOptimizationString \"%s\" rc=%d", opts.c_str(), rc);
    }
    Alog("compilation finish begin");
    int rc = Log("compilation_finish", NeuronCompilation_finish(C(c)));
    Alog("compilation finish end rc=%d", rc);
    return rc;
}
void CompilationFree(ANeuralNetworksCompilation* c) { NeuronCompilation_free(C(c)); }
int CompilationSetPreference(ANeuralNetworksCompilation* c, int32_t pref) {
    Alog("compilation setPreference %d", pref);
    return Log("setPreference", NeuronCompilation_setPreference(C(c), pref));
}
int CompilationSetCaching(ANeuralNetworksCompilation* c, const char* dir, const uint8_t* token) {
    Alog("compilation setCaching dir=%s skip=%d", dir ? dir : "(null)", g_no_cache.load() ? 1 : 0);
    if (g_no_cache.load()) return ANEURALNETWORKS_NO_ERROR;
    return Log("setCaching", NeuronCompilation_setCaching(C(c), dir, token));
}
int CompilationSetPriority(ANeuralNetworksCompilation*, int) { return ANEURALNETWORKS_NO_ERROR; }
int CompilationSetTimeout(ANeuralNetworksCompilation*, uint64_t) { return ANEURALNETWORKS_NO_ERROR; }
int CompilationAlignment(const ANeuralNetworksCompilation*, uint32_t, uint32_t* a) { *a = 64; return ANEURALNETWORKS_NO_ERROR; }
int CompilationPadding(const ANeuralNetworksCompilation*, uint32_t, uint32_t* p) { *p = 1; return ANEURALNETWORKS_NO_ERROR; }

// ---- execution ----
// Neuron copies inputs when setInput is called and its executions are not meant to be computed twice, while the TFLite
// delegate (feature level 5+) marks executions reusable and binds inputs once. So the shim keeps its own execution
// record with every binding and runs each compute on a fresh Neuron execution.
struct Binding {
    int32_t index;
    bool hasType;
    NeuronOperandType type;
    std::vector<uint32_t> dims;
    void* buf;
    size_t len;
};
struct ShimExecution {
    NeuronCompilation* compilation;
    std::vector<Binding> inputs, outputs;
    NeuronExecution* last = nullptr;  // kept so output ranks / dimensions can be queried after compute
};
inline ShimExecution* X(ANeuralNetworksExecution* e) { return reinterpret_cast<ShimExecution*>(e); }

void Bind(std::vector<Binding>& v, int32_t i, const ANeuralNetworksOperandType* t, const void* buf, size_t len) {
    Binding b{i, t != nullptr, {}, {}, const_cast<void*>(buf), len};
    if (t) {
        b.type = *T(t);
        b.dims.assign(t->dimensions, t->dimensions + t->dimensionCount);
    }
    for (auto& x : v) {
        if (x.index == i) { x = b; return; }
    }
    v.push_back(b);
}

int Apply(NeuronExecution* ne, const std::vector<Binding>& v, bool input) {
    for (auto& b : v) {
        NeuronOperandType t = b.type;
        t.dimensions = b.dims.empty() ? nullptr : b.dims.data();
        const NeuronOperandType* tp = b.hasType ? &t : nullptr;
        int rc = input ? NeuronExecution_setInput(ne, b.index, tp, b.buf, b.len)
                       : NeuronExecution_setOutput(ne, b.index, tp, b.buf, b.len);
        if (rc != NEURON_NO_ERROR) return rc;
    }
    return NEURON_NO_ERROR;
}

int ExecutionCreate(ANeuralNetworksCompilation* c, ANeuralNetworksExecution** e) {
    *e = reinterpret_cast<ANeuralNetworksExecution*>(new ShimExecution{C(c), {}, {}, nullptr});
    return ANEURALNETWORKS_NO_ERROR;
}
void ExecutionFree(ANeuralNetworksExecution* e) {
    if (!e) return;
    if (X(e)->last) NeuronExecution_free(X(e)->last);
    delete X(e);
}
int ExecutionSetInput(ANeuralNetworksExecution* e, int32_t i, const ANeuralNetworksOperandType* t, const void* buf, size_t len) {
    Bind(X(e)->inputs, i, t, buf, len);
    return ANEURALNETWORKS_NO_ERROR;
}
int ExecutionSetOutput(ANeuralNetworksExecution* e, int32_t i, const ANeuralNetworksOperandType* t, void* buf, size_t len) {
    Bind(X(e)->outputs, i, t, buf, len);
    return ANEURALNETWORKS_NO_ERROR;
}
int ExecutionSetInputFromMemory(ANeuralNetworksExecution* e, int32_t i, const ANeuralNetworksOperandType* t,
                                const ANeuralNetworksMemory* mem, size_t off, size_t len) {
    Bind(X(e)->inputs, i, t, At(mem, off), len);
    return ANEURALNETWORKS_NO_ERROR;
}
int ExecutionSetOutputFromMemory(ANeuralNetworksExecution* e, int32_t i, const ANeuralNetworksOperandType* t,
                                 const ANeuralNetworksMemory* mem, size_t off, size_t len) {
    Bind(X(e)->outputs, i, t, At(mem, off), len);
    return ANEURALNETWORKS_NO_ERROR;
}
int ExecutionCompute(ANeuralNetworksExecution* e) {
    Trace("compute begin");
    ShimExecution* x = X(e);
    if (x->last) { NeuronExecution_free(x->last); x->last = nullptr; }
    NeuronExecution* ne = nullptr;
    int rc = NeuronExecution_create(x->compilation, &ne);
    if (rc == NEURON_NO_ERROR) rc = Apply(ne, x->inputs, true);
    if (rc == NEURON_NO_ERROR) rc = Apply(ne, x->outputs, false);
    if (rc == NEURON_NO_ERROR) rc = NeuronExecution_compute(ne);
    x->last = ne;
    Trace("compute end");
    return Log("compute", rc);
}
// Synchronous stand-ins for the async / reuse / burst entry points, so the delegate never calls a null pointer.
struct ShimEvent { int dummy; };
int ExecutionStartComputeWithDeps(ANeuralNetworksExecution* e, const ANeuralNetworksEvent* const*, uint32_t, uint64_t,
                                  ANeuralNetworksEvent** ev) {
    Trace("startComputeWithDependencies -> compute");
    int rc = ExecutionCompute(e);
    *ev = reinterpret_cast<ANeuralNetworksEvent*>(new ShimEvent{0});
    return rc;
}
int EventWait(ANeuralNetworksEvent*) { return ANEURALNETWORKS_NO_ERROR; }
void EventFree(ANeuralNetworksEvent* ev) { delete reinterpret_cast<ShimEvent*>(ev); }
int ExecutionSetReusable(ANeuralNetworksExecution*, bool) { Trace("setReusable"); return ANEURALNETWORKS_NO_ERROR; }
int ExecutionEnablePadding(ANeuralNetworksExecution*, bool) { Trace("enableInputAndOutputPadding"); return ANEURALNETWORKS_NO_ERROR; }
struct ShimBurst { int dummy; };
int BurstCreate(ANeuralNetworksCompilation*, ANeuralNetworksBurst** b) {
    *b = reinterpret_cast<ANeuralNetworksBurst*>(new ShimBurst{0});
    return ANEURALNETWORKS_NO_ERROR;
}
void BurstFree(ANeuralNetworksBurst* b) { delete reinterpret_cast<ShimBurst*>(b); }
int BurstCompute(ANeuralNetworksExecution* e, ANeuralNetworksBurst*) { Trace("burstCompute -> compute"); return ExecutionCompute(e); }
int ExecutionGetOutputRank(ANeuralNetworksExecution* e, int32_t i, uint32_t* rank) {
    if (!X(e)->last) return ANEURALNETWORKS_BAD_STATE;
    return Log("getOutputOperandRank", NeuronExecution_getOutputOperandRank(X(e)->last, i, rank));
}
int ExecutionGetOutputDims(ANeuralNetworksExecution* e, int32_t i, uint32_t* dims) {
    if (!X(e)->last) return ANEURALNETWORKS_BAD_STATE;
    return Log("getOutputOperandDimensions", NeuronExecution_getOutputOperandDimensions(X(e)->last, i, dims));
}
int ExecutionSetMeasureTiming(ANeuralNetworksExecution*, bool) { return ANEURALNETWORKS_NO_ERROR; }
int ExecutionGetDuration(const ANeuralNetworksExecution*, int32_t, uint64_t* d) { *d = UINT64_MAX; return ANEURALNETWORKS_NO_ERROR; }
int ExecutionSetLoopTimeout(ANeuralNetworksExecution*, uint64_t) { return ANEURALNETWORKS_NO_ERROR; }
int ExecutionSetTimeout(ANeuralNetworksExecution*, uint64_t) { return ANEURALNETWORKS_NO_ERROR; }

NnApiSLDriverImplFL5 MakeImpl() {
    NnApiSLDriverImplFL5 s;
    memset(&s, 0, sizeof(s));  // everything not listed stays null = "not available"
    s.base.implFeatureLevel = FeatureLevel();  // FL5 struct layout, but advertise FL7 so FL6+ ops (BATCH_MATMUL ...) are delegated
    s.ANeuralNetworks_getDeviceCount = GetDeviceCount;
    s.ANeuralNetworks_getDevice = GetDevice;
    s.ANeuralNetworksDevice_getName = DeviceGetName;
    s.ANeuralNetworksDevice_getType = DeviceGetType;
    s.ANeuralNetworksDevice_getVersion = DeviceGetVersion;
    s.ANeuralNetworksDevice_getFeatureLevel = DeviceGetFeatureLevel;
    s.ANeuralNetworksDevice_wait = DeviceWait;
    s.ANeuralNetworksDevice_getExtensionSupport = DeviceGetExtensionSupport;
    s.ANeuralNetworks_getRuntimeFeatureLevel = RuntimeFeatureLevel;
    s.ANeuralNetworks_getDefaultLoopTimeout = DefaultLoopTimeout;
    s.ANeuralNetworks_getMaximumLoopTimeout = MaximumLoopTimeout;
    s.ANeuralNetworksMemory_createFromFd = MemoryCreateFromFd;
    s.ANeuralNetworksMemory_free = MemoryFree;
    s.ANeuralNetworksModel_create = ModelCreate;
    s.ANeuralNetworksModel_free = ModelFree;
    s.ANeuralNetworksModel_finish = ModelFinish;
    s.ANeuralNetworksModel_addOperand = ModelAddOperand;
    s.ANeuralNetworksModel_setOperandValue = ModelSetOperandValue;
    s.ANeuralNetworksModel_setOperandValueFromMemory = ModelSetOperandValueFromMemory;
    s.ANeuralNetworksModel_setOperandSymmPerChannelQuantParams = ModelSetSymmPerChannel;
    s.ANeuralNetworksModel_addOperation = ModelAddOperation;
    s.ANeuralNetworksModel_identifyInputsAndOutputs = ModelIdentifyIO;
    s.ANeuralNetworksModel_relaxComputationFloat32toFloat16 = ModelRelaxFp16;
    s.ANeuralNetworksModel_getSupportedOperationsForDevices = ModelGetSupportedOps;
    s.ANeuralNetworksCompilation_createForDevices = CompilationCreateForDevices;
    s.ANeuralNetworksCompilation_finish = CompilationFinish;
    s.ANeuralNetworksCompilation_free = CompilationFree;
    s.ANeuralNetworksCompilation_setPreference = CompilationSetPreference;
    s.ANeuralNetworksCompilation_setCaching = CompilationSetCaching;
    s.ANeuralNetworksCompilation_setPriority = CompilationSetPriority;
    s.ANeuralNetworksCompilation_setTimeout = CompilationSetTimeout;
    s.ANeuralNetworksCompilation_getPreferredMemoryAlignmentForInput = CompilationAlignment;
    s.ANeuralNetworksCompilation_getPreferredMemoryAlignmentForOutput = CompilationAlignment;
    s.ANeuralNetworksCompilation_getPreferredMemoryPaddingForInput = CompilationPadding;
    s.ANeuralNetworksCompilation_getPreferredMemoryPaddingForOutput = CompilationPadding;
    s.ANeuralNetworksExecution_create = ExecutionCreate;
    s.ANeuralNetworksExecution_free = ExecutionFree;
    s.ANeuralNetworksExecution_setInput = ExecutionSetInput;
    s.ANeuralNetworksExecution_setOutput = ExecutionSetOutput;
    s.ANeuralNetworksExecution_setInputFromMemory = ExecutionSetInputFromMemory;
    s.ANeuralNetworksExecution_setOutputFromMemory = ExecutionSetOutputFromMemory;
    s.ANeuralNetworksExecution_compute = ExecutionCompute;
    s.ANeuralNetworksExecution_getOutputOperandRank = ExecutionGetOutputRank;
    s.ANeuralNetworksExecution_getOutputOperandDimensions = ExecutionGetOutputDims;
    s.ANeuralNetworksExecution_setMeasureTiming = ExecutionSetMeasureTiming;
    s.ANeuralNetworksExecution_getDuration = ExecutionGetDuration;
    s.ANeuralNetworksExecution_setLoopTimeout = ExecutionSetLoopTimeout;
    s.ANeuralNetworksExecution_setTimeout = ExecutionSetTimeout;
    s.ANeuralNetworksExecution_startComputeWithDependencies = ExecutionStartComputeWithDeps;
    s.ANeuralNetworksExecution_setReusable = ExecutionSetReusable;
    s.ANeuralNetworksExecution_enableInputAndOutputPadding = ExecutionEnablePadding;
    s.ANeuralNetworksExecution_burstCompute = BurstCompute;
    s.ANeuralNetworksBurst_create = BurstCreate;
    s.ANeuralNetworksBurst_free = BurstFree;
    s.ANeuralNetworksEvent_wait = EventWait;
    s.ANeuralNetworksEvent_free = EventFree;
    return s;
}

}  // namespace

extern "C" __attribute__((visibility("default"))) NnApiSLDriverImpl* ANeuralNetworks_getSLDriverImpl() {
    static NnApiSLDriverImplFL5 impl = MakeImpl();
    return reinterpret_cast<NnApiSLDriverImpl*>(&impl);
}

// JNI entry points for the Android app (class com.bob.recordinghelper.asr.NpuBridge).
// supportLibraryHandle() feeds NnApiDelegate.Options.setNnApiSupportLibraryHandle().
extern "C" JNIEXPORT jlong JNICALL Java_com_bob_recordinghelper_asr_NpuBridge_supportLibraryHandle(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(ANeuralNetworks_getSLDriverImpl());
}

// Names of the NPU devices the Neuron adapter can see, comma separated ("" when none).
extern "C" JNIEXPORT jstring JNICALL Java_com_bob_recordinghelper_asr_NpuBridge_deviceNames(JNIEnv* env, jclass) {
    std::string out;
    uint32_t n = 0;
    if (Neuron_getDeviceCount(&n) == NEURON_NO_ERROR) {
        for (uint32_t i = 0; i < n; ++i) {
            NeuronDevice* d = nullptr;
            const char* name = nullptr;
            if (Neuron_getDevice(i, &d) == NEURON_NO_ERROR && NeuronDevice_getName(d, &name) == NEURON_NO_ERROR && name) {
                if (!out.empty()) out += ",";
                out += name;
            }
        }
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_bob_recordinghelper_asr_NpuBridge_setVerbose(JNIEnv*, jclass, jboolean on) {
    g_verbose = on;
}

extern "C" JNIEXPORT void JNICALL Java_com_bob_recordinghelper_asr_NpuBridge_setNoCache(JNIEnv*, jclass, jboolean on) {
    g_no_cache = on;
}

// Neuron runtime version as "major.minor.patch".
extern "C" JNIEXPORT jstring JNICALL Java_com_bob_recordinghelper_asr_NpuBridge_version(JNIEnv* env, jclass) {
    NeuronRuntimeVersion v{};
    char buf[32] = "?";
    if (Neuron_getVersion(&v) == NEURON_NO_ERROR) snprintf(buf, sizeof buf, "%u.%u.%u", v.major, v.minor, v.patch);
    return env->NewStringUTF(buf);
}

// Extra Neuron compiler options for every following compilation ("" to clear).
extern "C" JNIEXPORT void JNICALL Java_com_bob_recordinghelper_asr_NpuBridge_setCompileOptions(JNIEnv* env, jclass, jstring options) {
    const char* chars = options ? env->GetStringUTFChars(options, nullptr) : nullptr;
    {
        std::lock_guard<std::mutex> l(g_mu);
        g_compile_options = chars ? chars : "";
    }
    if (chars) env->ReleaseStringUTFChars(options, chars);
}
