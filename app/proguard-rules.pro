# sherpa-onnx's native code looks up its Kotlin classes, fields and methods by name (JNI), and its
# AAR ships no keep rules of its own, so renaming or stripping any of them crashes transcription.
-keep class com.k2fsa.sherpa.onnx.** { *; }
