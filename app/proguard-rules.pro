# Proguard rules for Gemini Summarizer
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Room Database
-keepclassmembers class * {
    @androidx.room.* *;
}
-keep class com.gemini.summarizer.core.database.entity.** { *; }
-keep class com.gemini.summarizer.core.database.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase

# Gson and Data Models
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.gemini.summarizer.core.network.** { *; }
-keep class com.gemini.summarizer.domain.model.** { *; }
-keep class com.gemini.summarizer.domain.engine.** { *; }

# Jsoup
-keep class org.jsoup.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

