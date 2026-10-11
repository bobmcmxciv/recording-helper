-keep class com.bob.recordinghelper.local.OfflinePipelineTest { *; }
-keep class com.bob.recordinghelper.local.DeviceSetupTest { *; }
# Compile-time-only Error Prone annotation value; javax.lang.model is not an Android runtime API.
-dontwarn javax.lang.model.element.Modifier
