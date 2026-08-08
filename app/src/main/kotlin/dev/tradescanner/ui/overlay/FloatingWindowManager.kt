package dev.tradescanner.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
    private var tvSlTp: TextView? = null
    private var tvScanCount: TextView? = null

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
            tvSlTp = floatingView!!.findViewById(R.id.tvSlTp)
            tvScanCount = floatingView!!.findViewById(R.id.tvScanCount)

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
                tvStatus?.text = if (isPaused) "متوقف شده" else "در حال اسکن..."
                callback.onPauseClicked(isPaused)
            }

            btnSettings.setOnClickListener {
                callback.onSettingsClicked()
            }

            // Drag handling
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
                        // Double tap detection for mini mode
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
            Log.d("FloatingMgr", "Floating window shown")
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
    }

    private fun toggleMiniMode() {
        isMini = !isMini
        floatingView?.let { view ->
            val status = view.findViewById<TextView>(R.id.tvFloatStatus)
            val sltp = view.findViewById<TextView>(R.id.tvSlTp)
            val count = view.findViewById<TextView>(R.id.tvScanCount)
            val buttons = view.findViewById<View>(R.id.btnFloatPause)?.parent as? View
            if (isMini) {
                status?.visibility = View.GONE
                sltp?.visibility = View.GONE
                count?.visibility = View.GONE
                buttons?.visibility = View.GONE
                view.layoutParams?.let {
                    params?.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params?.height = WindowManager.LayoutParams.WRAP_CONTENT
                }
            } else {
                status?.visibility = View.VISIBLE
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
            if (price == null) {
                tvPrice?.text = displayText
            } else {
                tvPrice?.text = displayText
            }
        }
    }

    fun updateSlTp(text: String) {
        tvSlTp?.post { tvSlTp?.text = text }
    }

    fun updateScanCount(count: Int) {
        tvScanCount?.post { tvScanCount?.text = "$count scans" }
    }
}
