package dev.tradescanner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import dev.tradescanner.model.OrderConfig
import dev.tradescanner.model.PendingOrderType
import java.io.ByteArrayOutputStream

class PreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("trade_scanner_prefs", Context.MODE_PRIVATE)

    fun saveOrderConfig(config: OrderConfig) {
        prefs.edit()
            .putString("sl", config.stopLoss)
            .putString("tp", config.takeProfit)
            .putFloat("lot", config.lotSize.toFloat())
            .putString("symbol", config.symbol)
            .putString("orderType", config.orderTypePref.name)
            .putLong("interval", config.scanIntervalMs)
            .putBoolean("deleteOnDisappear", config.deleteOnDisappear)
            .apply()
    }

    fun loadOrderConfig(): OrderConfig {
        return OrderConfig(
            stopLoss = prefs.getString("sl", "100") ?: "100",
            takeProfit = prefs.getString("tp", "200") ?: "200",
            lotSize = prefs.getFloat("lot", 0.01f).toDouble(),
            symbol = prefs.getString("symbol", "") ?: "",
            orderTypePref = try {
                PendingOrderType.valueOf(prefs.getString("orderType", "AUTO") ?: "AUTO")
            } catch (e: Exception) { PendingOrderType.AUTO },
            scanIntervalMs = prefs.getLong("interval", 900),
            deleteOnDisappear = prefs.getBoolean("deleteOnDisappear", true)
        )
    }

    // Template images stored as base64
    fun saveTemplate(type: String, bitmap: Bitmap) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
        prefs.edit().putString("template_$type", base64).apply()
    }

    fun loadTemplate(type: String): Bitmap? {
        val base64 = prefs.getString("template_$type", null) ?: return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

    fun clearTemplates() {
        prefs.edit().remove("template_buy").remove("template_sell").apply()
    }

    fun saveMediaProjectionData(resultCode: Int, data: android.content.Intent) {
        // Store intent for service restart. We store resultCode and serialized intent extras via Preferences?
        // Since Intent is Parcelable, we store its URI string representation via toUri
        prefs.edit()
            .putInt("mp_resultCode", resultCode)
            .putString("mp_data", data.toUri(0))
            .apply()
    }

    fun getMediaProjectionData(): Pair<Int, android.content.Intent?> {
        val code = prefs.getInt("mp_resultCode", -1)
        val uri = prefs.getString("mp_data", null) ?: return Pair(-1, null)
        return try {
            val intent = android.content.Intent.parseUri(uri, 0)
            Pair(code, intent)
        } catch (e: Exception) { Pair(-1, null) }
    }

    var lastDetectedPrice: Double
        get() = prefs.getFloat("last_price", Float.NaN).toDouble().let { if (it.isNaN()) Double.NaN else it }
        set(value) { prefs.edit().putFloat("last_price", value.toFloat()).apply() }
}
