package dev.tradescanner.analyzer

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.tradescanner.util.BitmapUtils
import kotlinx.coroutines.tasks.await

class PriceOcr {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractPriceNearMarker(originalBitmap: Bitmap, markerRect: Rect): Pair<Double?, String> {
        // Define ROI around marker: price label is usually to the right or directly on marker
        // TradingView Long/Short Position tool shows price at the label near the middle right of box
        // We'll search 5 regions: right of marker, left, above marker, marker itself expanded, below

        val candidates = listOf(
            Rect(markerRect.right, markerRect.top - 40, markerRect.right + 400, markerRect.bottom + 40),
            Rect(markerRect.left - 400, markerRect.top - 40, markerRect.left, markerRect.bottom + 40),
            Rect(markerRect.left, markerRect.top - 150, markerRect.right + 300, markerRect.top + 20),
            Rect(markerRect.left - 50, markerRect.top - 50, markerRect.right + 350, markerRect.bottom + 50),
            Rect(markerRect.left, markerRect.centerY() - 80, markerRect.right + 400, markerRect.centerY() + 80)
        )

        var bestPrice: Double? = null
        var bestRaw = ""

        for (roi in candidates) {
            val cropped = BitmapUtils.cropBitmap(originalBitmap, roi) ?: continue
            val preprocessed = BitmapUtils.preprocessForOcr(cropped)

            try {
                val image = InputImage.fromBitmap(preprocessed, 0)
                val result = recognizer.process(image).await()
                val text = result.text
                // Also check blocks
                val price = BitmapUtils.extractNumberFromText(text)
                if (price != null && price > 0) {
                    // Validate reasonable price ranges for typical assets
                    if (price > 0 && price < 1000000) {
                        bestPrice = price
                        bestRaw = text
                        cropped.recycle()
                        preprocessed.recycle()
                        break // take first found
                    }
                }
            } catch (e: Exception) {
                // try next
            } finally {
                if (!cropped.isRecycled) cropped.recycle()
                if (!preprocessed.isRecycled) preprocessed.recycle()
            }
        }

        // Fallback: if still not found, try OCR on entire marker region with less preprocessing
        if (bestPrice == null) {
            try {
                val expanded = Rect(
                    (markerRect.left - 20).coerceAtLeast(0),
                    (markerRect.top - 20).coerceAtLeast(0),
                    (markerRect.right + 300).coerceAtMost(originalBitmap.width),
                    (markerRect.bottom + 20).coerceAtMost(originalBitmap.height)
                )
                val cropped = BitmapUtils.cropBitmap(originalBitmap, expanded) ?: return Pair(null, "")
                val image = InputImage.fromBitmap(cropped, 0)
                val result = recognizer.process(image).await()
                val txt = result.text
                val price = BitmapUtils.extractNumberFromText(txt)
                cropped.recycle()
                if (price != null) {
                    bestPrice = price
                    bestRaw = txt
                }
            } catch (e: Exception) {}
        }

        return Pair(bestPrice, bestRaw)
    }

    fun close() {
        recognizer.close()
    }
}
