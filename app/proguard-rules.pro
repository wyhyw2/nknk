# BandSpark 混淆规则（MVP 默认不开启混淆）
-keep class com.bandspark.huohua.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
