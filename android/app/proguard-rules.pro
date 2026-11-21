-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Loaded by name inside the Shizuku helper process; the AIDL stub must keep its transaction layout.
-keep class com.agentos.workspace.WorkspaceShell { *; }
-keep class com.agentos.workspace.IWorkspaceShell { *; }
-keep class com.agentos.workspace.IWorkspaceShell$* { *; }
-keep class rikka.shizuku.** { *; }

# Wake word (voice/WakeWord): sherpa-onnx's JNI library reads its config and result classes by field name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
