# androidx.work / appcompat 自带 consumer rules，无需额外 keep

# 本地转写：JNI 方法按类名+方法名绑定，混淆后会找不到
-keep class com.bob.recordinghelper.asr.NpuBridge { *; }
-keep class com.bob.recordinghelper.local.** { *; }
-keep class com.bob.recordinghelper.Prefs { *; }
# Choice-dialog regression tests run against the minified release APK.
-keep class androidx.appcompat.app.AlertDialog { *; }
-keep class com.bob.recordinghelper.PhoneAsrBatch { *; }
-keep class com.bob.recordinghelper.PhoneAsrBatch$* { *; }
-keep class com.bob.recordinghelper.LocalAsr { *; }
-keep class com.bob.recordinghelper.LocalAsr$* { *; }
-keep class com.bob.recordinghelper.asr.AudioDecoder { *; }
-keep class com.bob.recordinghelper.asr.Transcriber$Result { *; }
-keep class com.bob.recordinghelper.ModelGate { *; }
-keep class com.bob.recordinghelper.KeepAwake { *; }
# The release instrumentation runner shares this dependency with WorkManager.
# Preserve its ABI: the test APK does not contain a second copy.
-keep class androidx.tracing.** { *; }
# AndroidJUnitRunner / monitor use the target APK's deduplicated Kotlin runtime.
# R8 otherwise removes facade classes (e.g. LazyKt) that only androidTest needs.
-keep class kotlin.** { *; }
# 本地大模型：JNI 按名字绑定 LlmEngine 的 native 方法，并回调 Sink.onToken(byte[])
-keep class com.bob.recordinghelper.llm.LlmEngine { *; }
-keep class com.bob.recordinghelper.llm.LlmEngine$* { *; }
-keep interface com.bob.recordinghelper.llm.LlmEngine$Sink { *; }
-keep class * implements com.bob.recordinghelper.llm.LlmEngine$Sink { void onToken(byte[]); }
# 高通 QNN 委托：native 层按名字回调 Options 的 getter
-keep class com.qualcomm.qti.** { *; }
