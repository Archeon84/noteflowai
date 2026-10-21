# Gson
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Data classes used with Gson (keep field names for serialization)
-keep class com.noteflowai.app.data.** { *; }
-keep class com.noteflowai.app.data.chat.** { *; }
-keep class com.noteflowai.app.util.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Exceptions

# Google Drive API
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }

# Whisper JNI
-keep class com.noteflowai.app.whisper.** { *; }

# Llama JNI
-keep class com.noteflowai.app.data.LlamaInferenceManager { *; }

# Keep data class members for Gson
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# PdfBox-Android
-keep class org.apache.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**

# Apache POI (DOCX)
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.xmlbeans.**
-keep class org.openxmlformats.** { *; }
-dontwarn org.openxmlformats.**

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Google LiteRT-LM (AI Edge On-Device LLM)
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ONNX Runtime Android (Granite Embeddings & GTE Reranker)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# SQLCipher Android (AES-256 Room Database Encryption)
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**

