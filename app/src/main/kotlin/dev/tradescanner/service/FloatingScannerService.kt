package dev.tradescanner.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import dev.tradescanner.MainActivity
import dev.tradescanner.R
import dev.tradescanner.analyzer.ScreenAnalyzer
import dev.tradescanner.automation.MT5Automator
import dev.tradescanner.model.DetectedSignal
import dev.tradescanner.model.PendingOrderState
import dev.tradescanner.model.SignalType
import dev.tradescanner.ui.overlay.FloatingWindowManager
import dev.tradescanner.util.PreferencesManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class FloatingScannerService : Service() {

    companion object {
        private const val TAG = "FloatingScannerSvc"
        const val CHANNEL_ID = "scanner_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
    }

    private var windowManager: WindowManager? = null
    private var floatingManager: FloatingWindowManager? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    private var preferences: PreferencesManager? = null
    private var analyzer: ScreenAnalyzer? = null

    private var scanJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var pendingState = PendingOrderState()
    private var lastDetectedPrice: Double? = null
    private var lastDetectionTime: Long = 0
    private var noSignalCount = 0
    private var totalScans = 0
    private var isPaused = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        preferences = PreferencesManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        floatingManager = FloatingWindowManager(this, windowManager!!, object : FloatingWindowManager.Callback {
            override fun onCloseClicked() { stopSelf() }
            override fun onPauseClicked(paused: Boolean) { isPaused = paused }
            override fun onSettingsClicked() {
                val intent = Intent(this@FloatingScannerService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand ${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopScanning()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra(EXTRA_DATA) as? Intent
                }

                startForegroundWithNotification()

                if (resultCode != -1 && data != null) {
                    // Save for restart
                    preferences?.saveMediaProjectionData(resultCode, data)
                    setupMediaProjection(resultCode, data)
                } else {
                    // Try to restore previous media projection
                    val (savedCode, savedData) = preferences?.getMediaProjectionData() ?: Pair(-1, null)
                    if (savedCode != -1 && savedData != null) {
                        setupMediaProjection(savedCode, savedData)
                    } else {
                        // If no media projection, still show floating window but scanning will be limited to accessibility takeScreenshot (API 30+)
                        Log.w(TAG, "No MediaProjection data, using accessibility screenshot fallback if available")
                    }
                }

                showFloatingWindow()
                startScanningLoop()
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Scanning...", "در حال اسکن صفحه")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(this, FloatingScannerService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_scanner), NotificationManager.IMPORTANCE_LOW)
            channel.description = "Trade scanner foreground service"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            val metrics = DisplayMetrics()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val windowMetrics = windowManager?.currentWindowMetrics
                    val bounds = windowMetrics?.bounds
                    screenWidth = bounds?.width() ?: 1080
                    screenHeight = bounds?.height() ?: 1920
                    screenDensity = resources.displayMetrics.densityDpi
                } else {
                    @Suppress("DEPRECATION")
                    windowManager?.defaultDisplay?.getRealMetrics(metrics)
                    screenWidth = metrics.widthPixels
                    screenHeight = metrics.heightPixels
                    screenDensity = metrics.densityDpi
                }
            } catch (e: Exception) {
                val dm = resources.displayMetrics
                screenWidth = dm.widthPixels
                screenHeight = dm.heightPixels
                screenDensity = dm.densityDpi
            }

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.w(TAG, "MediaProjection stopped")
                    stopScanning()
                }
            }, null)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "TradeScannerDisplay",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            Log.d(TAG, "MediaProjection setup done ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "setupMediaProjection error", e)
        }
    }

    private fun showFloatingWindow() {
        try {
            floatingManager?.show()
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingWindow error", e)
        }
    }

    private fun startScanningLoop() {
        val config = preferences?.loadOrderConfig()
        val textConfig = preferences?.loadTextTriggerConfig()
        val interval = config?.scanIntervalMs ?: 900L

        // Load templates if any
        val buyTemplate = preferences?.loadTemplate("buy")
        val sellTemplate = preferences?.loadTemplate("sell")
        analyzer = ScreenAnalyzer(buyTemplate, sellTemplate, textConfig)

        MT5Automator.getInstance().updateConfig(config ?: dev.tradescanner.model.OrderConfig())

        scanJob?.cancel()
        scanJob = serviceScope.launch {
            Log.d(TAG, "Scanning loop started interval=$interval")
            while (isActive) {
                if (!isPaused) {
                    try {
                        val bitmap = captureScreen()
                        if (bitmap != null) {
                            totalScans++
                            val signals = analyzer?.analyze(bitmap) ?: emptyList()
                            handleScanResult(signals)
                            bitmap.recycle()
                        } else {
                            // Try accessibility screenshot fallback (Android 11+)
                            val fallback = tryAccessibilityScreenshot()
                            if (fallback != null) {
                                totalScans++
                                val signals = analyzer?.analyze(fallback) ?: emptyList()
                                handleScanResult(signals)
                                fallback.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "scan loop error", e)
                    }
                }
                delay(interval)
            }
        }
    }

    private fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            // Crop to original width
            val cropped = if (bitmap.width > screenWidth) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also { bitmap.recycle() }
            } else bitmap
            return cropped
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen error", e)
            return null
        } finally {
            image.close()
        }
    }

    private suspend fun tryAccessibilityScreenshot(): Bitmap? {
        // Android 11+ AccessibilityService.takeScreenshot() can capture silently
        return withContext(Dispatchers.Main) {
            try {
                val service = TradeScannerAccessibilityService.instance ?: return@withContext null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // This API is async with callback
                    var resultBitmap: Bitmap? = null
                    val latch = CompletableDeferred<Bitmap?>()
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            try {
                                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                if (bitmap != null) {
                                    // Copy to software bitmap for processing
                                    val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    latch.complete(software)
                                    bitmap.recycle()
                                } else {
                                    latch.complete(null)
                                }
                                screenshot.hardwareBuffer.close()
                            } catch (e: Exception) {
                                latch.complete(null)
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            latch.complete(null)
                        }
                    })
                    try {
                        withTimeout(3000) { latch.await() }
                    } catch (e: Exception) { null }
                } else null
            } catch (e: Exception) { null }
        }
    }

    private suspend fun handleScanResult(signals: List<DetectedSignal>) {
        withContext(Dispatchers.Main) {
            if (signals.isEmpty()) {
                noSignalCount++
                // If had previous signal and now gone for N consecutive scans, delete pending order
                if (lastDetectedPrice != null && noSignalCount >= 4) { // ~ 3-4 seconds
                    val config = preferences?.loadOrderConfig()
                    if (config?.deleteOnDisappear == true) {
                        Log.d(TAG, "Signal disappeared, deleting order price=$lastDetectedPrice")
                        floatingManager?.updateStatus("سیگنال حذف شد - در حال حذف اردر...", null)
                        val result = MT5Automator.getInstance().deletePendingOrder(lastDetectedPrice)
                        Log.d(TAG, "delete result $result")
                        pendingState = PendingOrderState()
                        lastDetectedPrice = null
                        floatingManager?.updatePrice(null, "بدون سیگنال - اردر حذف شد")
                    } else {
                        noSignalCount = 0 // keep order
                    }
                } else {
                    floatingManager?.updateStatus("در حال اسکن... ($totalScans) - بدون سیگنال", null)
                    floatingManager?.updatePrice(null, "بدون سیگنال")
                }
            } else {
                noSignalCount = 0
                // Take strongest signal (highest confidence) or first BUY if exists
                val best = signals.maxByOrNull { it.confidence } ?: signals.first()
                Log.d(TAG, "Detected ${best.type} price=${best.price} conf=${best.confidence} raw=${best.rawOcrText}")
                lastDetectionTime = System.currentTimeMillis()

                // Check if price changed
                val tolerance = preferences?.loadOrderConfig()?.priceTolerance ?: 0.01
                val priceChanged = lastDetectedPrice == null || kotlin.math.abs(lastDetectedPrice!! - best.price) > tolerance

                if (priceChanged) {
                    if (pendingState.placedPrice != null) {
                        floatingManager?.updateStatus("قیمت تغییر کرد ${pendingState.placedPrice} -> ${best.price} حذف اردر قبلی", best)
                        // Delete previous
                        MT5Automator.getInstance().deletePendingOrder(pendingState.placedPrice)
                        delay(800)
                    }

                    // Place new pending order
                    floatingManager?.updateStatus("سیگنال جدید ${best.type} قیمت ${best.price} - ثبت اردر...", best)
                    val result = MT5Automator.getInstance().placePendingOrder(best.price, best.type)
                    Log.d(TAG, "place result $result for price ${best.price}")

                    when (result) {
                        is dev.tradescanner.model.AutomationResult.Success -> {
                            pendingState = PendingOrderState(placedPrice = best.price, orderType = null, placedTime = System.currentTimeMillis())
                            lastDetectedPrice = best.price
                            floatingManager?.updatePrice(best.price, "${best.type} @ ${best.price}")
                            updateNotification("سفارش ثبت شد @ ${best.price}", "${best.type} pending order placed")
                        }
                        is dev.tradescanner.model.AutomationResult.Failure -> {
                            floatingManager?.updateStatus("خطا در ثبت سفارش: ${result.reason}", best)
                        }
                        else -> {}
                    }
                } else {
                    // Same price, keep existing
                    floatingManager?.updateStatus("سیگنال پایدار @ ${best.price}", best)
                    floatingManager?.updatePrice(best.price, "${best.type} @ ${best.price} (پایدار)")
                }
            }
            floatingManager?.updateScanCount(totalScans)
            val cfg = preferences?.loadOrderConfig()
            floatingManager?.updateSlTp("SL:${cfg?.stopLoss} TP:${cfg?.takeProfit} Lot:${cfg?.lotSize}")
        }
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(title, content))
    }

    private fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}
        try { mediaProjection?.stop() } catch (e: Exception) {}
        analyzer?.release()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        floatingManager?.hide()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopScanning()
        serviceScope.cancel()
    }
}
