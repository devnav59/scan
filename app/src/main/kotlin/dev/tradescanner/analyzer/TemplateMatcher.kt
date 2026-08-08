package dev.tradescanner.analyzer

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.sqrt

/**
 * Pure Kotlin template matching without OpenCV - normalized cross-correlation sliding window.
 * Works for small templates. For better accuracy, app also supports OpenCV if available.
 * Tries to load OpenCV via reflection; if fails falls back to Kotlin implementation.
 */
class TemplateMatcher {

    data class MatchResult(val rect: Rect, val confidence: Float)

    fun match(screen: Bitmap, template: Bitmap, threshold: Float = 0.75f): List<MatchResult> {
        // Attempt OpenCV first
        tryOpenCvMatch(screen, template, threshold)?.let { return it }

        // Fallback pure Kotlin - downscaled to improve performance
        return kotlinMatch(screen, template, threshold)
    }

    private fun tryOpenCvMatch(screen: Bitmap, template: Bitmap, threshold: Float): List<MatchResult>? {
        return try {
            // Using reflection to avoid hard dependency - com.quickbirdstudios:opencv
            // Expected classes: org.opencv.android.Utils, org.opencv.core.Mat, Imgproc
            // If library not present, exception triggers fallback
            Class.forName("org.opencv.core.Mat")
            // If we reached here, OpenCV is present, use Java implementation
            OpenCvMatcher.match(screen, template, threshold)
        } catch (e: Throwable) {
            null
        }
    }

    private fun kotlinMatch(screen: Bitmap, template: Bitmap, threshold: Float): List<MatchResult> {
        val results = mutableListOf<MatchResult>()
        if (template.width > screen.width || template.height > screen.height) return results
        // Downscale both for performance, keep aspect
        val scale = if (screen.width > 1080) 1080f / screen.width else 1f
        val sW = (screen.width * scale).toInt()
        val sH = (screen.height * scale).toInt()
        val tW = (template.width * scale).toInt().coerceAtLeast(10)
        val tH = (template.height * scale).toInt().coerceAtLeast(10)

        val smallScreen = Bitmap.createScaledBitmap(screen, sW, sH, false)
        val smallTemplate = Bitmap.createScaledBitmap(template, tW, tH, false)

        // Precompute template mean & norm
        val templatePixels = IntArray(tW * tH)
        smallTemplate.getPixels(templatePixels, 0, tW, 0, 0, tW, tH)
        val templateGray = templatePixels.map { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            (0.299 * r + 0.587 * g + 0.114 * b)
        }
        val templateMean = templateGray.average()
        val templateNorm = sqrt(templateGray.sumOf { (it - templateMean) * (it - templateMean) }).toFloat()
        if (templateNorm == 0f) {
            smallScreen.recycle()
            smallTemplate.recycle()
            return results
        }

        val step = 8 // scanning step to improve performance
        for (y in 0..sH - tH step step) {
            for (x in 0..sW - tW step step) {
                // quick early reject: compare average color difference
                // Compute region stats
                var sum = 0.0
                var sumSq = 0.0
                var cross = 0.0
                var idx = 0
                for (ty in 0 until tH) {
                    for (tx in 0 until tW) {
                        val pixel = smallScreen.getPixel(x + tx, y + ty)
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val gray = 0.299 * r + 0.587 * g + 0.114 * b
                        sum += gray
                        sumSq += gray * gray
                        cross += (gray - 0) * (templateGray[idx] - templateMean) // will adjust after mean
                        idx++
                    }
                }
                val regionMean = sum / (tW * tH)
                // recompute cross with corrected mean
                var correctedCross = 0.0
                var regionVar = 0.0
                idx = 0
                for (ty in 0 until tH) {
                    for (tx in 0 until tW) {
                        val pixel = smallScreen.getPixel(x + tx, y + ty)
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val gray = 0.299 * r + 0.587 * g + 0.114 * b
                        val diffR = gray - regionMean
                        val diffT = templateGray[idx] - templateMean
                        correctedCross += diffR * diffT
                        regionVar += diffR * diffR
                        idx++
                    }
                }
                val regionNorm = sqrt(regionVar).toFloat()
                if (regionNorm == 0f) continue
                val ncc = (correctedCross / (regionNorm * templateNorm)).toFloat()
                if (ncc >= threshold) {
                    val origX = (x / scale).toInt()
                    val origY = (y / scale).toInt()
                    val origW = (tW / scale).toInt()
                    val origH = (tH / scale).toInt()
                    results.add(MatchResult(Rect(origX, origY, origX + origW, origY + origH), ncc))
                }
            }
        }
        smallScreen.recycle()
        smallTemplate.recycle()

        // Non-max suppression - remove overlapping
        return nonMaxSuppression(results)
    }

    private fun nonMaxSuppression(matches: List<MatchResult>): List<MatchResult> {
        if (matches.isEmpty()) return matches
        val sorted = matches.sortedByDescending { it.confidence }
        val final = mutableListOf<MatchResult>()
        for (m in sorted) {
            val overlaps = final.any { existing ->
                Rect.intersects(existing.rect, m.rect)
            }
            if (!overlaps) final.add(m)
        }
        return final
    }
}

/**
 * OpenCV matcher if library present, isolated to avoid ClassNotFound at load time unless invoked
 */
object OpenCvMatcher {
    fun match(screen: Bitmap, template: Bitmap, threshold: Float): List<TemplateMatcher.MatchResult> {
        try {
            val utilsClass = Class.forName("org.opencv.android.Utils")
            val matClass = Class.forName("org.opencv.core.Mat")
            val imgprocClass = Class.forName("org.opencv.imgproc.Imgproc")
            val coreClass = Class.forName("org.opencv.core.Core")

            // Convert bitmaps to Mat
            val screenMat = matClass.getDeclaredConstructor().newInstance()
            val templateMat = matClass.getDeclaredConstructor().newInstance()

            val bitmapToMatMethod = utilsClass.getMethod("bitmapToMat", android.graphics.Bitmap::class.java, matClass)
            bitmapToMatMethod.invoke(null, screen, screenMat)
            bitmapToMatMethod.invoke(null, template, templateMat)

            // Convert to grayscale for matching?
            val resultCols = screen.width - template.width + 1
            val resultRows = screen.height - template.height + 1
            if (resultCols <=0 || resultRows <=0) return emptyList()

            val cvType = matClass.getField("CV_32FC1").get(null) as Int
            val resultMat = matClass.getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(resultRows, resultCols, cvType)

            val tmMethod = imgprocClass.getField("TM_CCOEFF_NORMED").get(null) as Int
            val matchTemplateMethod = imgprocClass.getMethod("matchTemplate", matClass, matClass, matClass, Int::class.java)
            matchTemplateMethod.invoke(null, screenMat, templateMat, resultMat, tmMethod)

            // minMaxLoc
            val minMaxLocMethod = coreClass.getMethod("minMaxLoc", matClass)
            val minMaxResult = minMaxLocMethod.invoke(null, resultMat)

            val maxValField = minMaxResult.javaClass.getField("maxVal")
            val maxLocField = minMaxResult.javaClass.getField("maxLoc")
            val maxVal = maxValField.get(minMaxResult) as Double
            val maxLoc = maxLocField.get(minMaxResult)
            val xField = maxLoc.javaClass.getField("x")
            val yField = maxLoc.javaClass.getField("y")
            val x = (xField.get(maxLoc) as Double).toInt()
            val y = (yField.get(maxLoc) as Double).toInt()

            if (maxVal >= threshold) {
                return listOf(
                    TemplateMatcher.MatchResult(
                        Rect(x, y, x + template.width, y + template.height),
                        maxVal.toFloat()
                    )
                )
            }
        } catch (e: Throwable) {
            // ignore, fallback will be used
        }
        return emptyList()
    }
}
