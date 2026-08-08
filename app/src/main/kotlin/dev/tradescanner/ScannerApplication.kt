package dev.tradescanner

import android.app.Application
import android.util.Log

class ScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Attempt to load OpenCV if bundled
        try {
            // QuickBird OpenCV loader uses System.loadLibrary("opencv_java4")
            // org.opencv.android.OpenCVLoader.initDebug() is another way
            val loaderClass = Class.forName("org.opencv.android.OpenCVLoader")
            val method = loaderClass.getMethod("initDebug")
            val result = method.invoke(null) as Boolean
            Log.d("ScannerApp", "OpenCV init: $result")
        } catch (e: Throwable) {
            Log.w("ScannerApp", "OpenCV not available, using pure Kotlin matcher", e)
        }
    }
}
