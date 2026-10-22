# R8 rules for the release build. Anything reached by REFLECTION must be kept, or the release app
# builds fine and then silently parses nothing / breaks export-import.

# Gson turns JSON into these classes by field name, so the names and fields must survive:
#  - data/ : MarketData (Room entity AND the History export/import file format), BootstrapFetchLog
#  - network/ : every Retrofit interface and response class (FRED, Frankfurter, Alpha Vantage, ...)
-keep class com.example.gbpusdpredictor.data.** { *; }
-keep class com.example.gbpusdpredictor.network.** { *; }
# ...and private data classes declared next to the code that parses them (HistoryActivity.BackupPayload,
# MarketRepository.OilPriceEntry / GiltYieldEntry, sentiment feed classes). Found with a release build:
# R8 had renamed BackupPayload.marketData to "a", so exports wrote {"a":[]} and could not be re-imported.
# Keeping the FIELD NAMES of every app class costs almost nothing in size.
-keepclassmembers class com.example.gbpusdpredictor.** { <fields>; }

# Generic signatures and annotations Gson / Retrofit read at runtime.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit with Kotlin suspend functions under R8 full mode (Retrofit 2.9 predates these built-in rules).
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# OkHttp / Okio optional platform classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The tree model JSON is read from assets/ with org.json (built into Android): nothing to keep.
# Room, WorkManager and AdMob ship their own consumer rules.
