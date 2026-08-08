package dev.tradescanner.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.Button
import android.widget.TextView
import dev.tradescanner.R
import dev.tradescanner.model.DetectedSignal
import android.util.Log

class FloatingWindowManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val callback: Callback
) {
    interface Callback {
        fun onCloseClicked()
        fun onPauseClicked(paused: Boolean)
        fun onSettingsClicked()
    }

    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var isPaused = false
    private var isMini = false

    private var tvStatus: TextView? = null
    private var tvPrice: TextView? = null
    private var tvType: TextView? = null
    private var tvRaw: TextView? = null
    private var tvDebug: TextView? = null
    private var tvSlTp: TextView? = null
    private var tvScanCount: TextView? = null
    private var tvBeep: TextView? = null

    private var toneGenerator: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show() {
        if (isShowing) return
        try {
            val inflater = LayoutInflater.from(context)
            floatingView = inflater.inflate(R.layout.floating_window, null)

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 200
            }

            tvStatus = floatingView!!.findViewById(R.id.tvFloatStatus)
            tvPrice = floatingView!!.findViewById(R.id.tvFloatPrice)
            tvType = floatingView!!.findViewById(R.id.tvFloatType)
            tvRaw = floatingView!!.findViewById(R.id.tvFloatRaw)
            tvDebug = floatingView!!.findViewById(R.id.tvFloatDebug)
            tvSlTp = floatingView!!.findViewById(R.id.tvSlTp)
            tvScanCount = floatingView!!.findViewById(R.id.tvScanCount)
            tvBeep = floatingView!!.findViewById(R.id.tvBeepIndicator)

            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            } catch (e: Exception) {
                Log.e("FloatingMgr", "ToneGenerator init failed", e)
            }

            val btnClose = floatingView!!.findViewById<View>(R.id.btnClose)
            val btnPause = floatingView!!.findViewById<Button>(R.id.btnFloatPause)
            val btnSettings = floatingView!!.findViewById<Button>(R.id.btnFloatSettings)

            btnClose.setOnClickListener {
                hide()
                callback.onCloseClicked()
            }

            btnPause.setOnClickListener {
                isPaused = !isPaused
                btnPause.text = if (isPaused) "Resume" else "Pause"
                tvStatus?.text = if (isPaused) "متوقف شده - Pause" else "در حال اسکن... - Active"
                tvDebug?.text = if (isPaused) "Paused" else "Resumed, waiting for signal..."
                callback.onPauseClicked(isPaused)
            }

            btnSettings.setOnClickListener {
                callback.onSettingsClicked()
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var lastClickTime = 0L

            floatingView!!.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params!!.x
                        initialY = params!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < 300) {
                            toggleMiniMode()
                        }
                        lastClickTime = now
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params!!.x = initialX + (event.rawX - initialTouchX).toInt()
                        params!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        try { windowManager.updateViewLayout(floatingView, params) } catch (e: Exception) {}
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(floatingView, params)
            isShowing = true
            Log.d("FloatingMgr", "Floating window shown with debug & beep")
            updateDebug("Floating window shown, waiting for OCR...")
        } catch (e: Exception) {
            Log.e("FloatingMgr", "Error showing floating", e)
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            floatingView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        floatingView = null
        isShowing = false
        try { toneGenerator?.release() } catch (e: Exception) {}
        toneGenerator = null
    }

    private fun toggleMiniMode() {
        isMini = !isMini
        floatingView?.let { view ->
            val status = view.findViewById<TextView>(R.id.tvFloatStatus)
            val debug = view.findViewById<TextView>(R.id.tvFloatDebug)
            val raw = view.findViewById<TextView>(R.id.tvFloatRaw)
            val sltp = view.findViewById<TextView>(R.id.tvSlTp)
            val count = view.findViewById<TextView>(R.id.tvScanCount)
            val buttons = view.findViewById<View>(R.id.btnFloatPause)?.parent as? View
            if (isMini) {
                status?.visibility = View.GONE
                debug?.visibility = View.GONE
                raw?.visibility = View.GONE
                sltp?.visibility = View.GONE
                count?.visibility = View.GONE
                buttons?.visibility = View.GONE
            } else {
                status?.visibility = View.VISIBLE
                debug?.visibility = View.VISIBLE
                raw?.visibility = View.VISIBLE
                sltp?.visibility = View.VISIBLE
                count?.visibility = View.VISIBLE
                buttons?.visibility = View.VISIBLE
            }
        }
    }

    fun updateStatus(status: String, signal: DetectedSignal?) {
        tvStatus?.post {
            tvStatus?.text = status
        }
    }

    fun updatePrice(price: Double?, displayText: String) {
        tvPrice?.post {
            tvPrice?.text = displayText
        }
    }

    fun updateSlTp(text: String) {
        tvSlTp?.post { tvSlTp?.text = text }
    }

    fun updateScanCount(count: Int) {
        tvScanCount?.post { tvScanCount?.text = "$count scans" }
    }

    fun showDetection(signal: DetectedSignal) {
        mainHandler.post {
            tvType?.text = "نوع: ${signal.type} - ${if (signal.type.name == "BUY") "خرید 🟢" else "فروش 🔴"}"
            tvType?.setTextColor(if (signal.type.name == "BUY") 0xFF00E676.toInt() else 0xFFFF5252.toInt())
            tvPrice?.text = "قیمت: ${signal.price}"
            tvPrice?.setTextColor(0xFFFFFF00.toInt())
            tvRaw?.text = "OCR: ${signal.rawOcrText.take(120)}"
            tvStatus?.text = "✅ سیگنال یافت شد @ ${signal.price}"
            tvBeep?.visibility = View.VISIBLE
            mainHandler.postDelayed({ tvBeep?.visibility = View.INVISIBLE }, 800)
        }
        playBeep(signal.type.name)
    }

    fun showNoSignalDebug(ocrLinesCount: Int, lastOcrTextSample: String) {
        mainHandler.post {
            tvType?.text = "نوع: --"
            tvType?.setTextColor(0xFFAAAAAA.toInt())
            tvPrice?.text = "بدون سیگنال - در حال رصد..."
            tvRaw?.text = "OCR lines: $ocrLinesCount | نمونه: ${lastOcrTextSample.take(80)}"
        }
    }

    fun updateDebug(debugText: String) {
        tvDebug?.post {
            tvDebug?.text = "Debug: $debugText"
        }
    }

    fun updateRawOcr(raw: String) {
        tvRaw?.post {
            tvRaw?.text = "OCR: $raw"
        }
    }

    private fun playBeep(type: String) {
        try {
            if (type == "BUY") {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
            } else {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            }
            mainHandler.postDelayed({
                try { toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200) } catch (e: Exception) {}
            }, 300)
        } catch (e: Exception) {
            Log.e("FloatingMgr", "Beep failed", e)
            try {
                floatingView?.post {
                    try {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audio.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                    } catch (ex: Exception) {}
                }
            } catch (ex: Exception) {}
        }
    }

    fun updateFromSignal(signal: DetectedSignal?, status: String, scanCount: Int, slTp: String, debug: String) {
        if (signal != null) {
            showDetection(signal)
        } else {
            tvStatus?.post { tvStatus?.text = status }
        }
        updateScanCount(scanCount)
        updateSlTp(slTp)
        updateDebug(debug)
    }
}
