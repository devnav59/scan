package dev.tradescanner.analyzer

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import dev.tradescanner.model.DetectedSignal
import dev.tradescanner.model.SignalType
import dev.tradescanner.util.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Core analyzer that scans entire screen bitmap for buy/sell signals
 * Supports two modes:
 * 1) Template mode - if user provided buy/sell template Bitmaps, uses template matching
 * 2) Color detection mode - if no templates, uses color thresholding for TradingView Long/Short labels
 *
 * After finding candidate rects, extracts price via PriceOcr
 */
class ScreenAnalyzer(
    private val buyTemplate: Bitmap? = null,
    private val sellTemplate: Bitmap? = null
) {
    private val templateMatcher = TemplateMatcher()
    private val priceOcr = PriceOcr()

    companion object {
        private const val TAG = "ScreenAnalyzer"
    }

    suspend fun analyze(bitmap: Bitmap): List<DetectedSignal> = withContext(Dispatchers.Default) {
        val results = mutableListOf<DetectedSignal>()
        try {
            Log.d(TAG, "analyze: screen ${bitmap.width}x${bitmap.height}, buyTemplate=${buyTemplate != null}, sellTemplate=${sellTemplate != null}")

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

            // If no templates or no results from templates, fallback to color detection
            if (results.isEmpty()) {
                val colorResults = analyzeByColor(bitmap)
                results.addAll(colorResults)
            }

        } catch (e: Exception) {
            Log.e(TAG, "analyze error", e)
        }
        return@withContext results
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
                    // Limit to avoid too many OCRs
                    if (results.size >= 3) break
                }
            }

            val sellRegions = BitmapUtils.findColorRegions(bitmap, isBuy = false)
            Log.d("ScreenAnalyzer", "color sell regions: ${sellRegions.size}")
            for (rect in sellRegions) {
                // Avoid duplicate with buy if close
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
        buyTemplate?.recycle()
        sellTemplate?.recycle()
    }
}
