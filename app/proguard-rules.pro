# Keep Xposed hook class
-keep class com.fakeethernet.MainHook { *; }
-keep class com.fakeethernet.SettingsActivity { *; }

# Don't obfuscate Xposed entry point
-keepclassmembers class com.fakeethernet.MainHook {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam);
}
