# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# Glass: the phone key helper runs from this APK as the TV shell user (app_process), not as part of the app.
-keep class com.gathin.tvhome.KeyDaemon { public static void main(java.lang.String[]); }
