package dev.tradescanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.RadioGroup
import android.widget.TextView
import dev.tradescanner.model.DetectionMode
import dev.tradescanner.model.OrderConfig
import dev.tradescanner.model.PendingOrderType
import dev.tradescanner.model.TextTriggerConfig
import dev.tradescanner.service.FloatingScannerService
import dev.tradescanner.util.PreferencesManager
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    private lateinit var etSl: TextInputEditText
    private lateinit var etTp: TextInputEditText
    private lateinit var etLot: TextInputEditText
    private lateinit var etSymbol: TextInputEditText
    private lateinit var etInterval: TextInputEditText
    private lateinit var rgOrderType: RadioGroup
    private lateinit var tvStatus: TextView
    private lateinit var tvBuyStatus: TextView
    private lateinit var tvSellStatus: TextView

    // New text trigger fields
    private lateinit var etBuyKeywords: TextInputEditText
    private lateinit var etSellKeywords: TextInputEditText
    private lateinit var etDecimalRegex: TextInputEditText
    private lateinit var etSearchRadius: TextInputEditText
    private lateinit var rgDetectionMode: RadioGroup

    private var mediaProjectionResultCode: Int = -1
    private var mediaProjectionData: Intent? = null

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) {
            toast("دسترسی Overlay داده شد")
            checkAllPermissions()
        }
    }

    private val accessibilitySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        toast("وضعیت Accessibility را بررسی کنید")
        checkAllPermissions()
    }

    private val mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionData = result.data
            result.data?.let {
                prefs.saveMediaProjectionData(result.resultCode, it)
            }
            toast("دسترسی ضبط صفحه داده شد")
            tvStatus.text = "MediaProjection آماده است - می‌توانید اسکن را شروع کنید"
        } else {
            toast("دسترسی ضبط صفحه رد شد - از fallback Accessibility استفاده خواهد شد")
        }
    }

    private val pickBuyLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            saveTemplateFromUri(it, "buy")
            tvBuyStatus.text = "الگوی خرید: تنظیم شد ✓"
        }
    }

    private val pickSellLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            saveTemplateFromUri(it, "sell")
            tvSellStatus.text = "الگوی فروش: تنظیم شد ✓"
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)

        etSl = findViewById(R.id.etStopLoss)
        etTp = findViewById(R.id.etTakeProfit)
        etLot = findViewById(R.id.etLot)
        etSymbol = findViewById(R.id.etSymbol)
        etInterval = findViewById(R.id.etInterval)
        rgOrderType = findViewById(R.id.rgOrderType)
        tvStatus = findViewById(R.id.tvStatus)
        tvBuyStatus = findViewById(R.id.tvBuyTemplateStatus)
        tvSellStatus = findViewById(R.id.tvSellTemplateStatus)

        etBuyKeywords = findViewById(R.id.etBuyKeywords)
        etSellKeywords = findViewById(R.id.etSellKeywords)
        etDecimalRegex = findViewById(R.id.etDecimalRegex)
        etSearchRadius = findViewById(R.id.etSearchRadius)
        rgDetectionMode = findViewById(R.id.rgDetectionMode)

        // Load saved config
        val cfg = prefs.loadOrderConfig()
        etSl.setText(cfg.stopLoss)
        etTp.setText(cfg.takeProfit)
        etLot.setText(cfg.lotSize.toString())
        etSymbol.setText(cfg.symbol)
        etInterval.setText(cfg.scanIntervalMs.toString())
        when (cfg.orderTypePref) {
            PendingOrderType.AUTO -> findViewById<android.widget.RadioButton>(R.id.rbAuto).isChecked = true
            PendingOrderType.BUY_LIMIT -> findViewById<android.widget.RadioButton>(R.id.rbBuyLimit).isChecked = true
            PendingOrderType.SELL_LIMIT -> findViewById<android.widget.RadioButton>(R.id.rbSellLimit).isChecked = true
            PendingOrderType.BUY_STOP -> findViewById<android.widget.RadioButton>(R.id.rbBuyStop).isChecked = true
            PendingOrderType.SELL_STOP -> findViewById<android.widget.RadioButton>(R.id.rbSellStop).isChecked = true
        }

        val txtCfg = prefs.loadTextTriggerConfig()
        etBuyKeywords.setText(txtCfg.buyKeywords)
        etSellKeywords.setText(txtCfg.sellKeywords)
        etDecimalRegex.setText(txtCfg.decimalRegex)
        etSearchRadius.setText(txtCfg.searchRadiusPx.toString())
        when (txtCfg.detectionMode) {
            DetectionMode.TEXT_TRIGGER -> findViewById<android.widget.RadioButton>(R.id.rbModeText).isChecked = true
            DetectionMode.HYBRID -> findViewById<android.widget.RadioButton>(R.id.rbModeHybrid).isChecked = true
            DetectionMode.IMAGE_TEMPLATE -> findViewById<android.widget.RadioButton>(R.id.rbModeImage).isChecked = true
            DetectionMode.COLOR_DETECTION -> findViewById<android.widget.RadioButton>(R.id.rbModeColor).isChecked = true
        }

        // Template status
        updateTemplateStatus()

        // Buttons
        findViewById<MaterialButton>(R.id.btnOverlay)?.setOnClickListener { requestOverlayPermission() }
        findViewById<MaterialButton>(R.id.btnAccessibility)?.setOnClickListener { requestAccessibilityPermission() }
        findViewById<MaterialButton>(R.id.btnMedia)?.setOnClickListener { requestMediaProjection() }

        findViewById<MaterialButton>(R.id.btnStart)?.setOnClickListener { startFloatingService() }
        findViewById<MaterialButton>(R.id.btnStop)?.setOnClickListener { stopFloatingService() }

        findViewById<MaterialButton>(R.id.btnPickBuy)?.setOnClickListener { pickBuyLauncher.launch("image/*") }
        findViewById<MaterialButton>(R.id.btnPickSell)?.setOnClickListener { pickSellLauncher.launch("image/*") }
        findViewById<MaterialButton>(R.id.btnClearTemplates)?.setOnClickListener {
            prefs.clearTemplates()
            updateTemplateStatus()
            toast("الگوها پاک شدند - حالت تشخیص رنگ فعال")
        }

        checkAllPermissions()

        // Restore mediaProjection data if any
        val (savedCode, savedIntent) = prefs.getMediaProjectionData()
        if (savedCode != -1 && savedIntent != null) {
            mediaProjectionResultCode = savedCode
            mediaProjectionData = savedIntent
        }
    }

    private fun updateTemplateStatus() {
        val buyExists = prefs.loadTemplate("buy") != null
        val sellExists = prefs.loadTemplate("sell") != null
        tvBuyStatus.text = if (buyExists) "الگوی خرید: تنظیم شد ✓" else "الگوی خرید: تنظیم نشده"
        tvSellStatus.text = if (sellExists) "الگوی فروش: تنظیم شد ✓" else "الگوی فروش: تنظیم نشده"
    }

    private fun saveTemplateFromUri(uri: Uri, type: String) {
        try {
            val input: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(input)
            input?.close()
            if (bitmap != null) {
                val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width.coerceAtMost(200), bitmap.height.coerceAtMost(200), true)
                prefs.saveTemplate(type, scaled)
                scaled.recycle()
                bitmap.recycle()
                toast("الگوی $type ذخیره شد")
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveTemplate error", e)
            toast("خطا در ذخیره الگو: ${e.message}")
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
        } else {
            toast("دسترسی Overlay از قبل داده شده")
        }
    }

    private fun requestAccessibilityPermission() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
        toast("در لیست باز شده، TradeScanner را فعال کنید")
    }

    private fun requestMediaProjection() {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mgr.createScreenCaptureIntent()
            mediaProjectionLauncher.launch(intent)
        } catch (e: Exception) {
            toast("خطا در درخواست MediaProjection: ${e.message}")
        }
    }

    private fun checkAllPermissions() {
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val sb = StringBuilder()
        sb.append("وضعیت:\n")
        sb.append("Overlay: ${if (overlayOk) "✓ OK" else "✗ نیاز به فعالسازی"}\n")
        sb.append("MediaProjection: ${if (mediaProjectionResultCode != -1) "✓ OK" else "⚠ پیشنهاد می‌شود"}\n")
        sb.append("Accessibility: سرویس TradeScanner باید روشن باشد\n")
        val txtCfg = prefs.loadTextTriggerConfig()
        sb.append("\nحالت فعلی: ${txtCfg.detectionMode}\n")
        sb.append("کلمات خرید: ${txtCfg.buyKeywords}\n")
        sb.append("کلمات فروش: ${txtCfg.sellKeywords}\n")
        tvStatus.text = sb.toString()
    }

    private fun getCurrentConfig(): OrderConfig {
        val sl = etSl.text?.toString() ?: "100"
        val tp = etTp.text?.toString() ?: "200"
        val lot = etLot.text?.toString()?.toDoubleOrNull() ?: 0.01
        val symbol = etSymbol.text?.toString() ?: ""
        val interval = etInterval.text?.toString()?.toLongOrNull() ?: 600L
        val orderType = when (rgOrderType.checkedRadioButtonId) {
            R.id.rbBuyLimit -> PendingOrderType.BUY_LIMIT
            R.id.rbSellLimit -> PendingOrderType.SELL_LIMIT
            R.id.rbBuyStop -> PendingOrderType.BUY_STOP
            R.id.rbSellStop -> PendingOrderType.SELL_STOP
            else -> PendingOrderType.AUTO
        }
        return OrderConfig(stopLoss = sl, takeProfit = tp, lotSize = lot, symbol = symbol, orderTypePref = orderType, scanIntervalMs = interval.coerceAtLeast(300))
    }

    private fun getCurrentTextConfig(): TextTriggerConfig {
        val buyKw = etBuyKeywords.text?.toString()?.ifBlank { "Buy, Long" } ?: "Buy, Long"
        val sellKw = etSellKeywords.text?.toString()?.ifBlank { "Sell, Short" } ?: "Sell, Short"
        val regex = etDecimalRegex.text?.toString()?.ifBlank { """\d+\.\d+""" } ?: """\d+\.\d+"""
        val radius = etSearchRadius.text?.toString()?.toIntOrNull() ?: 500
        val mode = when (rgDetectionMode.checkedRadioButtonId) {
            R.id.rbModeHybrid -> DetectionMode.HYBRID
            R.id.rbModeImage -> DetectionMode.IMAGE_TEMPLATE
            R.id.rbModeColor -> DetectionMode.COLOR_DETECTION
            else -> DetectionMode.TEXT_TRIGGER
        }
        return TextTriggerConfig(
            buyKeywords = buyKw,
            sellKeywords = sellKw,
            detectionMode = mode,
            decimalRegex = regex,
            searchRadiusPx = radius
        )
    }

    private fun startFloatingService() {
        val config = getCurrentConfig()
        val txtConfig = getCurrentTextConfig()
        prefs.saveOrderConfig(config)
        prefs.saveTextTriggerConfig(txtConfig)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            toast("لطفا اول دسترسی Overlay را بدهید")
            requestOverlayPermission()
            return
        }

        if (txtConfig.buyKeywords.isBlank() && txtConfig.sellKeywords.isBlank() && txtConfig.detectionMode == DetectionMode.TEXT_TRIGGER) {
            toast("لطفا حداقل یک کلمه کلیدی خرید یا فروش وارد کنید")
            return
        }

        val serviceIntent = Intent(this, FloatingScannerService::class.java).apply {
            if (mediaProjectionResultCode != -1 && mediaProjectionData != null) {
                putExtra(FloatingScannerService.EXTRA_RESULT_CODE, mediaProjectionResultCode)
                putExtra(FloatingScannerService.EXTRA_DATA, mediaProjectionData)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        toast("سرویس شناور با حالت ${txtConfig.detectionMode} شروع شد")
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingScannerService::class.java).apply { action = FloatingScannerService.ACTION_STOP }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        toast("سرویس متوقف شد")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
