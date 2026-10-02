# sherpa-onnx's native code looks up its Kotlin classes, fields and methods by name (JNI), and its
# AAR ships no keep rules of its own, so renaming or stripping any of them crashes transcription.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM (the summary model's engine) is driven from its native code over JNI and ships no
# rules of its own: keep it whole, or R8's renaming breaks it in release builds only.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
