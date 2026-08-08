package dev.tradescanner.analyzer

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.tradescanner.model.DetectedSignal
import dev.tradescanner.model.SignalType
import dev.tradescanner.model.TextTriggerConfig
import dev.tradescanner.model.parseBuyKeywords
import dev.tradescanner.model.parseSellKeywords
import dev.tradescanner.util.BitmapUtils
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * جدید: تشخیص بر اساس متن کلیدی + عدد اعشاری مقابل آن
 * کاربر متنی مثل "Buy" یا "Long" یا "خرید" وارد می‌کند، ما لحظه‌ای صفحه را رصد می‌کنیم
 * هرجا آن متن دیده شد، نزدیک‌ترین عدد اعشاری مقابل/کنار آن استخراج می‌شود و به عنوان قیمت سیگنال استفاده می‌شود
 *
 * مثال:
 *  - صفحه تریدینگ‌ویو: "Long 1934.56" -> keyword "Long" + number 1934.56 => BUY signal
 *  - "Sell 1.08542" -> SELL signal
 *  - فارسی: "خرید 1934.5" -> BUY
 *
 * این روش بسیار قابل اعتمادتر از تشخیص تصویر است چون TradingView متن را با کیفیت رندر می‌کند
 */
class TextTriggerAnalyzer(private val config: TextTriggerConfig) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        private const val TAG = "TextTriggerAnalyzer"
        var lastOcrFullText: String = ""
        var lastOcrLinesCount: Int = 0
        var lastSearchKeywords: String = ""
        // Regex های پیشرفته برای عدد اعشاری - از تنظیمات کاربر یا پیش‌فرض
        private val DEFAULT_DECIMAL_REGEXES = listOf(
            Regex("""\d{1,3}(?:,\d{3})*\.\d+"""), // 1,934.56
            Regex("""\d+\.\d{3,6}"""), // 1.08542 forex 5 decimals
            Regex("""\d+\.\d{1,5}"""), // 1934.56
            Regex("""\d+\.\d+""") // fallback
        )
    }

    data class OcrLine(
        val text: String,
        val boundingBox: Rect?,
        val blockIndex: Int,
        val lineIndex: Int
    )

    suspend fun analyze(bitmap: Bitmap): List<DetectedSignal> {
        val results = mutableListOf<DetectedSignal>()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(image).await()

            // Save debug info
            lastOcrFullText = visionText.text.take(500)
            lastOcrLinesCount = visionText.textBlocks.sumOf { it.lines.size }
            lastSearchKeywords = "${config.buyKeywords} | ${config.sellKeywords}"
            Log.d(TAG, "Full OCR: ${visionText.text.take(200)}")

            val allLines = mutableListOf<OcrLine>()
            for ((blockIdx, block) in visionText.textBlocks.withIndex()) {
                for ((lineIdx, line) in block.lines.withIndex()) {
                    val box = line.boundingBox ?: block.boundingBox
                    allLines.add(OcrLine(line.text, box, blockIdx, lineIdx))
                }
                // Also add block text as fallback
                if (block.lines.isEmpty()) {
                    allLines.add(OcrLine(block.text, block.boundingBox, blockIdx, -1))
                }
            }

            Log.d(TAG, "OCR found ${allLines.size} lines, full text length=${visionText.text.length}")

            // Parse keywords
            val buyKeywords = config.parseBuyKeywords()
            val sellKeywords = config.parseSellKeywords()
            val caseSensitive = config.caseSensitive

            Log.d(TAG, "Searching buyKeywords=$buyKeywords sellKeywords=$sellKeywords in ${allLines.size} lines")

            // Search for BUY signals
            for (keyword in buyKeywords) {
                val matches = findKeywordMatches(allLines, keyword, caseSensitive)
                for (matchLine in matches) {
                    val price = extractDecimalNearLine(matchLine, allLines, config.searchRadiusPx, config.decimalRegex)
                    if (price != null) {
                        val (priceValue, raw, confidence) = price
                        results.add(
                            DetectedSignal(
                                type = SignalType.BUY,
                                price = priceValue,
                                confidence = confidence,
                                boundingBox = matchLine.boundingBox ?: Rect(0,0,100,100),
                                rawOcrText = "Keyword:$keyword | Line:${matchLine.text} | Found:$raw"
                            )
                        )
                        Log.d(TAG, "BUY detected keyword=$keyword price=$priceValue near ${matchLine.text}")
                    }
                }
            }

            // Search for SELL signals
            for (keyword in sellKeywords) {
                val matches = findKeywordMatches(allLines, keyword, caseSensitive)
                for (matchLine in matches) {
                    // Avoid double counting if same line already matched as BUY
                    val alreadyMatchedAsBuy = results.any { it.boundingBox == matchLine.boundingBox && it.type == SignalType.BUY }
                    if (alreadyMatchedAsBuy) continue

                    val price = extractDecimalNearLine(matchLine, allLines, config.searchRadiusPx, config.decimalRegex)
                    if (price != null) {
                        val (priceValue, raw, confidence) = price
                        results.add(
                            DetectedSignal(
                                type = SignalType.SELL,
                                price = priceValue,
                                confidence = confidence,
                                boundingBox = matchLine.boundingBox ?: Rect(0,0,100,100),
                                rawOcrText = "Keyword:$keyword | Line:${matchLine.text} | Found:$raw"
                            )
                        )
                        Log.d(TAG, "SELL detected keyword=$keyword price=$priceValue near ${matchLine.text}")
                    }
                }
            }

            // If no results with strict radius, try more lenient fallback: search entire block for number
            if (results.isEmpty()) {
                Log.d(TAG, "No signal with nearby search, trying block-level fallback")
                for (block in visionText.textBlocks) {
                    val blockText = block.text
                    val containsBuy = buyKeywords.any { kw -> if (caseSensitive) blockText.contains(kw) else blockText.contains(kw, ignoreCase = true) }
                    val containsSell = sellKeywords.any { kw -> if (caseSensitive) blockText.contains(kw) else blockText.contains(kw, ignoreCase = true) }
                    if (containsBuy || containsSell) {
                        val price = extractDecimalFromText(blockText, config.decimalRegex)
                        if (price != null) {
                            val type = if (containsBuy) SignalType.BUY else SignalType.SELL
                            results.add(
                                DetectedSignal(
                                    type = type,
                                    price = price.first,
                                    confidence = 0.6f,
                                    boundingBox = block.boundingBox ?: Rect(0,0,200,100),
                                    rawOcrText = "Block fallback: ${blockText.take(100)} -> ${price.second}"
                                )
                            )
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "analyze error", e)
        }

        // Deduplicate: keep only highest confidence per type & close price
        return deduplicateSignals(results)
    }

    private fun findKeywordMatches(lines: List<OcrLine>, keyword: String, caseSensitive: Boolean): List<OcrLine> {
        return lines.filter { line ->
            if (caseSensitive) line.text.contains(keyword)
            else line.text.contains(keyword, ignoreCase = true)
        }
    }

    private data class ExtractedPrice(val value: Double, val rawText: String, val confidence: Float)

    private fun extractDecimalNearLine(
        targetLine: OcrLine,
        allLines: List<OcrLine>,
        radiusPx: Int,
        customRegex: String
    ): ExtractedPrice? {
        // First try same line
        val sameLinePrice = extractDecimalFromText(targetLine.text, customRegex)
        if (sameLinePrice != null) {
            return ExtractedPrice(sameLinePrice.first, sameLinePrice.second, 0.95f)
        }

        // Search nearby lines within radius
        val targetBox = targetLine.boundingBox ?: return null
        val targetCenterX = targetBox.centerX()
        val targetCenterY = targetBox.centerY()

        var best: ExtractedPrice? = null
        var bestDistance = Float.MAX_VALUE

        for (other in allLines) {
            if (other === targetLine) continue
            val otherBox = other.boundingBox ?: continue
            // Calculate distance
            val dx = otherBox.centerX() - targetCenterX
            val dy = otherBox.centerY() - targetCenterY
            val distance = sqrt((dx*dx + dy*dy).toDouble()).toFloat()
            if (distance > radiusPx) continue

            // Check if other line contains decimal number
            val price = extractDecimalFromText(other.text, customRegex)
            if (price != null) {
                // Prefer lines to the right or same horizontal level (TradingView usually shows price to the right of signal)
                var confidence = 0.8f
                // Boost if to the right
                if (otherBox.centerX() > targetBox.centerX()) confidence += 0.05f
                // Boost if same Y (same row)
                if (abs(otherBox.centerY() - targetBox.centerY()) < 80) confidence += 0.1f
                // Penalize distance
                confidence -= (distance / radiusPx) * 0.3f

                if (distance < bestDistance) {
                    bestDistance = distance
                    best = ExtractedPrice(price.first, price.second, confidence.coerceIn(0.3f, 0.95f))
                }
            }
        }

        return best
    }

    private fun extractDecimalFromText(text: String, customRegex: String?): Pair<Double, String>? {
        val regexes = mutableListOf<Regex>()
        // Add custom regex first if provided and valid
        if (!customRegex.isNullOrBlank()) {
            try {
                regexes.add(Regex(customRegex))
            } catch (e: Exception) {
                Log.w(TAG, "Invalid custom regex $customRegex")
            }
        }
        regexes.addAll(DEFAULT_DECIMAL_REGEXES)

        for (regex in regexes) {
            val matches = regex.findAll(text)
            for (match in matches) {
                val raw = match.value
                val cleaned = raw.replace(",", "")
                try {
                    val value = cleaned.toDouble()
                    // Filter unrealistic prices: must be >0 and < 1,000,000 and have decimal part
                    if (value > 0 && value < 1_000_000) {
                        // Additional check: for forex, allow 0.xxx, for gold etc >100
                        return Pair(value, raw)
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }

        // Fallback to BitmapUtils extract (handles more patterns)
        val fallback = BitmapUtils.extractNumberFromText(text)
        if (fallback != null) {
            return Pair(fallback, fallback.toString())
        }

        return null
    }

    private fun deduplicateSignals(signals: List<DetectedSignal>): List<DetectedSignal> {
        if (signals.isEmpty()) return signals
        // Group by type and close price (within 0.5 difference)
        val grouped = mutableListOf<DetectedSignal>()
        val sorted = signals.sortedByDescending { it.confidence }
        for (sig in sorted) {
            val closeExists = grouped.any { existing ->
                existing.type == sig.type && kotlin.math.abs(existing.price - sig.price) < 0.5
            }
            if (!closeExists) grouped.add(sig)
        }
        return grouped.take(3) // limit to 3 signals max
    }

    fun close() {
        recognizer.close()
    }
}
