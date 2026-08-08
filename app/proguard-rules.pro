# Keep OpenCV
-keep class org.opencv.** { *; }
-keep class com.quickbirdstudios.opencv.** { *; }

# Keep Accessibility
-keep class dev.tradescanner.service.** { *; }

# MLKit
-keep class com.google.mlkit.** { *; }

# Don't obfuscate model
-keep class dev.tradescanner.model.** { *; }
