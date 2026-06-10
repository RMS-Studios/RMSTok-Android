# Keep the JS interface methods so they aren't stripped/renamed in release builds.
-keepclassmembers class com.rmstudios.rmstok.** {
    @android.webkit.JavascriptInterface <methods>;
}
