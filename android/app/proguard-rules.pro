# gomobile bindings (JNI)
-keep class go.** { *; }
-keep class app.spicetify.android.spicengine.** { *; }

# Bridge methods called from JavaScript on old WebViews
-keepclassmembers class app.spicetify.android.web.NativeBridge$LegacyInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
