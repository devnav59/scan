package dev.tradescanner.analyzer

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import dev.tradescanner.model.DetectedSignal
import dev.tradescanner.model.DetectionMode
import dev.tradescanner.model.SignalType
import dev.tradescanner.model.TextTriggerConfig
import dev.tradescanner.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Core analyzer that scans entire screen bitmap for buy/sell signals
 * Supports multiple modes (now with TEXT_TRIGGER as primary per user request)
 * 1) TEXT_TRIGGER - user provides keywords like "Buy, Long" and we find decimal number near it
 * 2) TEMPLATE - if user provided buy/sell template Bitmaps, uses template matching
 * 3) COLOR detection - if no templates, uses color thresholding for TradingView Long/Short labels
 * 4) HYBRID - runs all and merges
 */
class ScreenAnalyzer(
    private val buyTemplate: Bitmap? = null,
    private val sellTemplate: Bitmap? = null,
    private val textTriggerConfig: TextTriggerConfig? = null
) {
    private val templateMatcher = TemplateMatcher()
    private val priceOcr = PriceOcr()
    private val textTriggerAnalyzer: TextTriggerAnalyzer? = textTriggerConfig?.let { TextTriggerAnalyzer(it) }

    companion object {
        private const val TAG = "ScreenAnalyzer"
    }

    suspend fun analyze(bitmap: Bitmap): List<DetectedSignal> = withContext(Dispatchers.Default) {
        val results = mutableListOf<DetectedSignal>()
        try {
            val mode = textTriggerConfig?.detectionMode ?: DetectionMode.HYBRID
            Log.d(TAG, "analyze mode=$mode screen ${bitmap.width}x${bitmap.height}, buyTemplate=${buyTemplate != null}, sellTemplate=${sellTemplate != null}, textConfig=${textTriggerConfig != null}")

            when (mode) {
                DetectionMode.TEXT_TRIGGER -> {
                    // فقط متن
                    textTriggerAnalyzer?.let {
                        val txtResults = it.analyze(bitmap)
                        results.addAll(txtResults)
                    }
                    // اگر نتیجه نداشت، fallback به رنگ
                    if (results.isEmpty()) {
                        results.addAll(analyzeByColor(bitmap))
                    }
                }
                DetectionMode.IMAGE_TEMPLATE -> {
                    val templResults = analyzeByTemplate(bitmap)
                    results.addAll(templResults)
                    if (results.isEmpty()) {
                        // fallback text
                        textTriggerAnalyzer?.let { results.addAll(it.analyze(bitmap)) }
                    }
                }
                DetectionMode.COLOR_DETECTION -> {
                    results.addAll(analyzeByColor(bitmap))
                }
                DetectionMode.HYBRID -> {
                    // اول متن (دقیق‌ترین برای درخواست جدید کاربر)
                    textTriggerAnalyzer?.let {
                        val txtResults = it.analyze(bitmap)
                        results.addAll(txtResults)
                        Log.d(TAG, "HYBRID: text trigger found ${txtResults.size}")
                    }
                    // اگر متن چیزی پیدا نکرد، template
                    if (results.isEmpty()) {
                        val templResults = analyzeByTemplate(bitmap)
                        results.addAll(templResults)
                        Log.d(TAG, "HYBRID: template found ${templResults.size}")
                    }
                    // اگر باز هم چیزی نبود، رنگ
                    if (results.isEmpty()) {
                        val colorResults = analyzeByColor(bitmap)
                        results.addAll(colorResults)
                        Log.d(TAG, "HYBRID: color found ${colorResults.size}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "analyze error", e)
        }
        return@withContext results
    }

    private suspend fun analyzeByTemplate(bitmap: Bitmap): List<DetectedSignal> {
        val results = mutableListOf<DetectedSignal>()
        try {
            if (buyTemplate != null) {
                val matches = templateMatcher.match(bitmap, buyTemplate, 0.75f)
                Log.d(TAG, "buy template matches: ${matches.size}")
                for (m in matches) {
                    val (price, raw) = priceOcr.extractPriceNearMarker(bitmap, m.rect)
                    if (price != null) {
                        results.add(
                            DetectedSignal(
                                type = SignalType.BUY,
                                price = price,
                                confidence = m.confidence,
                                boundingBox = m.rect,
                                rawOcrText = raw
                            )
                        )
                    }
                }
            }

            if (sellTemplate != null) {
                val matches = templateMatcher.match(bitmap, sellTemplate, 0.75f)
                Log.d(TAG, "sell template matches: ${matches.size}")
                for (m in matches) {
                    val (price, raw) = priceOcr.extractPriceNearMarker(bitmap, m.rect)
                    if (price != null) {
                        results.add(
                            DetectedSignal(
                                type = SignalType.SELL,
                                price = price,
                                confidence = m.confidence,
                                boundingBox = m.rect,
                                rawOcrText = raw
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "template analyze error", e)
        }
        return results
    }

    private suspend fun analyzeByColor(bitmap: Bitmap): List<DetectedSignal> {
        val results = mutableListOf<DetectedSignal>()
        try {
            val buyRegions = BitmapUtils.findColorRegions(bitmap, isBuy = true)
            Log.d("ScreenAnalyzer", "color buy regions: ${buyRegions.size}")
            for (rect in buyRegions) {
                val (price, raw) = priceOcr.extractPriceNearMarker(bitmap, rect)
                if (price != null) {
                    results.add(
                        DetectedSignal(
                            type = SignalType.BUY,
                            price = price,
                            confidence = 0.8f,
                            boundingBox = rect,
                            rawOcrText = raw
                        )
                    )
                    if (results.size >= 3) break
                }
            }

            val sellRegions = BitmapUtils.findColorRegions(bitmap, isBuy = false)
            Log.d("ScreenAnalyzer", "color sell regions: ${sellRegions.size}")
            for (rect in sellRegions) {
                val tooCloseToBuy = results.any { existing ->
                    Rect.intersects(existing.boundingBox, rect) || distance(existing.boundingBox, rect) < 100
                }
                if (tooCloseToBuy) continue
                val (price, raw) = priceOcr.extractPriceNearMarker(bitmap, rect)
                if (price != null) {
                    results.add(
                        DetectedSignal(
                            type = SignalType.SELL,
                            price = price,
                            confidence = 0.8f,
                            boundingBox = rect,
                            rawOcrText = raw
                        )
                    )
                    if (results.size >= 6) break
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenAnalyzer", "color analysis error", e)
        }
        return results
    }

    private fun distance(a: Rect, b: Rect): Int {
        val ax = a.centerX()
        val ay = a.centerY()
        val bx = b.centerX()
        val by = b.centerY()
        return kotlin.math.sqrt(((ax-bx)*(ax-bx)+(ay-by)*(ay-by)).toDouble()).toInt()
    }

    fun release() {
        priceOcr.close()
        textTriggerAnalyzer?.close()
        buyTemplate?.recycle()
        sellTemplate?.recycle()
    }
}
