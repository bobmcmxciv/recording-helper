package com.bob.recordinghelper.local

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.bob.recordinghelper.LocalAsr
import com.bob.recordinghelper.ModelGate
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** User-started, visible microphone FGS. Never starts at boot or restarts microphone capture after death. */
class MicrophoneService : Service() {
    private val stopCapture = AtomicBoolean(false)
    private val abortProcessing = AtomicBoolean(false)
    @Volatile private var captureDone = false
    @Volatile private var available = 0L
    @Volatile private var recorder: AudioRecord? = null
    private var wake: PowerManager.WakeLock? = null
    private var mode = "record"
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (!MicState.busy) { stopSelf(); return START_NOT_STICKY }
            if (mode != "record" || captureDone) abortProcessing.set(true)
            stopCapture.set(true)
            try { recorder?.stop() } catch (_: Exception) {}
            return START_NOT_STICKY
        }
        if (MicState.busy) return START_NOT_STICKY
        stopCapture.set(false); abortProcessing.set(false); captureDone = false; available = 0
        mode = intent?.getStringExtra("mode") ?: ""
        if (mode !in listOf("record", "self", "other", "identify")) { stopSelf(); return START_NOT_STICKY }
        val name = intent?.getStringExtra("name").orEmpty().trim().take(40)
        try {
            require(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "麦克风录音与声纹采样", NotificationManager.IMPORTANCE_LOW))
            val n = notification("正在准备麦克风，点击可返回操作页")
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NOTICE, n)
            MicState.update { MicState.Snapshot(busy = true, capturing = true, mode = mode, message = "准备录音；后台 AI 整理将暂缓") }
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recordinghelper:microphone").apply {
                setReferenceCounted(false); acquire(150 * 60_000L)
            }
            // Cooperative LLM cancellation releases the model gate; existing audio remains safe.
            WorkManager.getInstance(this).cancelUniqueWork("local-analysis")
            WorkManager.getInstance(this).cancelUniqueWork("local-analysis-now")
            thread(name = "rh-microphone") { runCapture(name, startId) }
        } catch (e: Exception) {
            MicState.update { it.copy(busy = false, capturing = false, message = e.message ?: "无法启动麦克风") }
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun runCapture(name: String, startId: Int) {
        var id = ""
        var processor: Thread? = null
        var error: String? = null
        try {
            require(mode != "other" || name.isNotBlank()) { "请填写对方名称" }
            if (mode != "record") VoiceLibrary.modelHash(this)
            val meta = MicArchive.create(this, mode, name); id = meta.getString("id")
            val wav = MicArchive.audio(this, id)
            val min = AudioRecord.getMinBufferSize(PcmWave.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0) { "设备不支持 16 kHz 单声道录音" }
            val builder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(PcmWave.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, 32000))
            if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(true)
            val ar = builder.build(); recorder = ar
            require(ar.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            val limit = when (mode) { "self", "other" -> 20L * PcmWave.RATE; "identify" -> 10L * PcmWave.RATE; else -> PcmWave.MAX_SAMPLES }
            PcmWave(wav).use { out ->
                ar.startRecording()
                require(ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风被占用或系统禁止录音" }
                MicState.update { it.copy(id = id, message = if (mode == "record") "正在录音 · 本地文字约每 5 秒更新" else "请由同一人自然朗读，自动结束后在本机处理") }
                if (mode == "record") processor = thread(name = "rh-live-asr") { transcribe(id, wav) }
                val buffer = ShortArray(1600)
                var notified = -1L
                while (!stopCapture.get() && available < limit) {
                    val n = ar.read(buffer, 0, minOf(buffer.size.toLong(), limit - available).toInt(), AudioRecord.READ_BLOCKING)
                    if (stopCapture.get()) break
                    require(n > 0) { "麦克风读取中断（$n），已录部分保留" }
                    require(wav.parentFile!!.usableSpace > 32L * 1024 * 1024) { "存储空间不足，已停止并保留录音" }
                    out.write(buffer, n); available = out.samples
                    val level = (0 until n).maxOf { kotlin.math.abs(buffer[it].toInt()) } * 100 / 32768
                    MicState.update { it.copy(samples = available, level = level) }
                    val sec = available / PcmWave.RATE
                    if (sec != notified) {
                        notified = sec
                        getSystemService(NotificationManager::class.java).notify(NOTICE, notification("${if (mode == "record") "录音" else "声纹采样"} ${sec} 秒 · 点击停止"))
                    }
                }
            }
        } catch (e: Exception) { if (!stopCapture.get()) error = e.message ?: "录音失败" }
        finally {
            try { recorder?.stop() } catch (_: Exception) {}
            runCatching { recorder?.release() }; recorder = null
            captureDone = true
            MicState.update { it.copy(capturing = false, level = 0, message = error ?: "录音结束，正在完成本地处理") }
        }
        try {
            if (id.isNotEmpty()) {
                MicArchive.update(this, id) { it.put("samples", available).put("state", "transcribing") }
                if (mode == "record") {
                    processor?.join()
                    val done = MicArchive.saved(this, id)
                    MicArchive.update(this, id) { it.put("state", if (done) "saved" else "waiting").put("error", error.orEmpty()) }
                    MicState.update { it.copy(message = error ?: if (done) "录音与转写已保存，可在资料库查看" else "原录音已保存；可在下方继续转写，已有分段文字保留") }
                } else if (!abortProcessing.get() && error == null) {
                    val committed = processVoice(id, name)
                    if (abortProcessing.get() && !committed) MicState.update { it.copy(message = "已取消采样或比对，原声纹未更改") }
                } else MicState.update { it.copy(message = error ?: "已取消采样，原声纹未更改") }
            }
        } catch (e: Exception) {
            MicState.update { it.copy(message = e.message ?: "本地处理失败，未覆盖已有声纹") }
        } finally {
            if (id.isNotEmpty() && mode != "record") {
                MicArchive.audio(this, id).delete() // ephemeral enrollment/verification audio only
                runCatching { MicArchive.update(this, id) { it.put("state", "finished") } }
            }
            Handler(Looper.getMainLooper()).post {
                MicState.update { it.copy(busy = false, capturing = false, level = 0) }
                if (wake?.isHeld == true) wake?.release()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
                ImportWorker.schedule(this); RecordingQueueWorker.schedule(this); AnalysisWorker.schedule(this)
            }
        }
    }
    private fun transcribe(id: String, wav: File) {
        if (!LocalAsr.modelReady(this)) { MicState.update { it.copy(message = "仅录音：语音模型未安装，原音会保留") }; return }
        try {
            MicState.update { it.copy(message = "录音进行中，等待模型资源；不会丢弃音频") }
            ModelGate.interactive({ !abortProcessing.get() }) {
                LocalAsr.open(this, cpuOnly = true, loadSpeakers = false).use { asr ->
                    val voice = runCatching { VoiceEngine(File(LocalAsr.modelDir(this), "spk.tflite")) }.getOrNull()
                    try {
                        val library = runCatching { VoiceLibrary.compatible(this) }.getOrDefault(emptyMap())
                        val unknown = arrayListOf<FloatArray>()
                        var offset = 0L
                        val rows = arrayListOf<String>()
                        while (!abortProcessing.get()) {
                            val remaining = available - offset
                            if (remaining < CHUNK && !captureDone) { Thread.sleep(100); continue }
                            if (remaining <= 0) break
                            val n = minOf(CHUNK.toLong(), remaining).toInt()
                            val pcm = PcmWave.read(wav, offset, n)
                            val text = asr.transcribePcm(pcm)
                            var speaker = ""; var score = -1.0
                            if (text.isNotBlank() && voice != null) {
                                val sample = voice.extract(pcm)
                                if (sample.vectors.size >= 2) {
                                    val vector = VoiceMatcher.center(sample.vectors)
                                    if (sample.vectors.all { VoiceMatcher.score(it, vector) >= .60 }) {
                                        val match = VoiceMatcher.match(vector, library); score = match.score
                                        speaker = if (match.name != null) "疑似${match.name}" else {
                                            val best = unknown.indices.maxByOrNull { VoiceMatcher.score(unknown[it], vector) }
                                            val index = if (best != null && VoiceMatcher.score(unknown[best], vector) >= VoiceMatcher.THRESHOLD) best
                                                else if (unknown.size < 12) { unknown.add(vector); unknown.lastIndex } else -1
                                            if (index >= 0) "未匹配说话人${index + 1}" else "未匹配"
                                        }
                                    } else speaker = "说话人不确定"
                                }
                            }
                            if (text.isNotBlank()) rows.add((if (speaker.isNotBlank()) "$speaker：" else "") + text)
                            val start = offset; offset += n
                            MicArchive.update(this, id) { it.getJSONArray("segments").put(JSONObject().put("start", start / 16000.0)
                                .put("end", offset / 16000.0).put("text", text).put("speaker", speaker).put("similarity", score)) }
                            MicState.update { it.copy(processed = offset, text = rows.takeLast(100).joinToString("\n"),
                                message = "已转写 ${offset / 16000} 秒 · 声纹为实验性提示，分数不是概率" + if (score >= 0) " · 相似度 %.2f".format(score) else "") }
                        }
                        if (!abortProcessing.get() && offset == available && captureDone) {
                            val meta = MicArchive.get(this, id)
                            val recordId = LocalStore.of(this).transcript(MicArchive.key(this, id), meta.getString("title"),
                                rows.joinToString("\n").ifBlank { "（未识别到语音）" }, java.time.Instant.ofEpochMilli(meta.getLong("created")).toString())
                            if (rows.isEmpty()) LocalStore.of(this).status(recordId, "silent")
                        }
                    } finally { voice?.close() }
                }
            }
        } catch (e: Exception) {
            MicState.update { it.copy(message = "实时转写暂不可用，继续保留原录音：${e.message?.take(80)}") }
        }
    }
    private fun processVoice(id: String, name: String): Boolean {
        var committed = false
        MicState.update { it.copy(message = "正在本机检查声纹样本；无需上传") }
        ModelGate.interactive({ !abortProcessing.get() }) {
            val hash = VoiceLibrary.modelHash(this)
            VoiceEngine(File(LocalAsr.modelDir(this), "spk.tflite")).use { engine ->
                val pcm = PcmWave.read(MicArchive.audio(this, id), 0, available.toInt())
                val sample = engine.extract(pcm)
                require(sample.voicedSeconds >= if (mode == "identify") 3 else 8) { "有效语音不足，请靠近麦克风，在安静环境自然说话后重试" }
                require(VoiceMatcher.coherent(sample.vectors)) { "样本不一致或噪声较大，请只由一人重新录入；旧声纹保留" }
                if (abortProcessing.get()) return@interactive
                if (mode == "identify") {
                    val match = VoiceMatcher.match(VoiceMatcher.center(sample.vectors), VoiceLibrary.compatible(this))
                    MicState.update { it.copy(message = (match.name?.let { n -> "疑似$n" } ?: "未可靠匹配到声纹库中的人") +
                        if (match.score >= 0) " · 相似度 %.2f（非概率，不用于身份验证）".format(match.score) else " · 请先录入声纹") }
                } else {
                    val profile = VoiceLibrary.of(this).put(if (mode == "self") "我的声音" else name, mode == "self", hash, sample.vectors)
                    committed = true
                    MicState.update { it.copy(message = "已保存「${profile.name}」的本地声纹；原始采样音频已安排删除。可再点辨认检验效果") }
                }
            }
        }
        return committed
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 800, Intent(this, MicrophoneActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 801, Intent(this, MicrophoneService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(com.bob.recordinghelper.R.drawable.ic_mic)
            .setContentTitle(if (mode == "record") "正在使用麦克风录音" else "声纹采样 / 本地比对")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(com.bob.recordinghelper.R.drawable.ic_stop, "停止", stop).build()
    }
    override fun onDestroy() {
        stopCapture.set(true); abortProcessing.set(true)
        try { recorder?.stop() } catch (_: Exception) {}
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
    companion object { const val STOP = "rh.microphone.STOP"; private const val CHANNEL = "microphone"; private const val NOTICE = 800; const val CHUNK = 5 * 16000 }
}
