package dev.tradescanner.util

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import kotlin.math.abs

object BitmapUtils {
    fun cropBitmap(src: Bitmap, rect: Rect): Bitmap? {
        val safeRect = Rect(
            rect.left.coerceAtLeast(0),
            rect.top.coerceAtLeast(0),
            rect.right.coerceAtMost(src.width),
            rect.bottom.coerceAtMost(src.height)
        )
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return null
        return Bitmap.createBitmap(src, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
    }

    fun scaleBitmap(src: Bitmap, scale: Float): Bitmap {
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    fun toGrayscale(src: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val pixel = src.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                bmp.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
        }
        return bmp
    }

    /**
     * Simple color threshold for green buy signals (#26A69A, #2962FF blue for long) and red sell signals
     * Returns list of rects where color mass is high
     */
    fun findColorRegions(bitmap: Bitmap, isBuy: Boolean): List<Rect> {
        val regions = mutableListOf<Rect>()
        val width = bitmap.width
        val height = bitmap.height
        val scaleDown = 4
        val smallW = width / scaleDown
        val smallH = height / scaleDown
        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, false)

        val visited = Array(smallH) { BooleanArray(smallW) }
        for (y in 0 until smallH) {
            for (x in 0 until smallW) {
                if (visited[y][x]) continue
                val pixel = small.getPixel(x, y)
                if (isSignalColor(pixel, isBuy)) {
                    // flood fill to get blob
                    val blob = floodFill(small, x, y, isBuy, visited)
                    if (blob.size > 5) {
                        val minX = blob.minOf { it.first } * scaleDown
                        val maxX = blob.maxOf { it.first } * scaleDown
                        val minY = blob.minOf { it.second } * scaleDown
                        val maxY = blob.maxOf { it.second } * scaleDown
                        val rect = Rect(minX, minY, maxX + scaleDown, maxY + scaleDown)
                        // filter by aspect ratio and size - tradingview labels are horizontal rectangles
                        if (rect.width() > 30 && rect.height() > 10 && rect.width() < width * 0.8 && rect.height() < 150) {
                            regions.add(rect)
                        }
                    }
                }
            }
        }
        small.recycle()
        return regions
    }

    private fun floodFill(
        bmp: Bitmap,
        startX: Int,
        startY: Int,
        isBuy: Boolean,
        visited: Array<BooleanArray>
    ): MutableList<Pair<Int, Int>> {
        val queue = ArrayDeque<Pair<Int,Int>>()
        val blob = mutableListOf<Pair<Int,Int>>()
        queue.add(startX to startY)
        visited[startY][startX] = true
        val maxBlobSize = 5000
        while (queue.isNotEmpty() && blob.size < maxBlobSize) {
            val (x,y) = queue.removeFirst()
            blob.add(x to y)
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx==0 && dy==0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until bmp.width && ny in 0 until bmp.height && !visited[ny][nx]) {
                        visited[ny][nx] = true
                        if (isSignalColor(bmp.getPixel(nx, ny), isBuy)) {
                            queue.add(nx to ny)
                        }
                    }
                }
            }
        }
        return blob
    }

    private fun isSignalColor(pixel: Int, isBuy: Boolean): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val hsv = FloatArray(3)
        Color.RGBToHSV(r,g,b,hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]
        return if (isBuy) {
            // Buy signals: greenish #26A69A hue ~174, or blue #2962FF hue ~224
            // Allow two ranges
            val isGreen = hue in 140f..190f && sat > 0.3f && value > 0.3f
            val isBlue = hue in 210f..240f && sat > 0.5f && value > 0.4f
            isGreen || isBlue
        } else {
            // Sell signals: red #EF5350 hue ~0-10 or 350-360
            val isRed = (hue in 0f..15f || hue in 340f..360f) && sat > 0.4f && value > 0.3f
            isRed
        }
    }

    fun preprocessForOcr(bitmap: Bitmap): Bitmap {
        // Increase contrast, upscale 3x, grayscale, threshold
        val scaled = scaleBitmap(bitmap, 3f)
        val gray = toGrayscale(scaled)
        // simple binary threshold
        val result = Bitmap.createBitmap(gray.width, gray.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until gray.height) {
            for (x in 0 until gray.width) {
                val p = gray.getPixel(x, y)
                val g = Color.red(p)
                val thresh = if (g > 140) 255 else 0
                result.setPixel(x, y, Color.rgb(thresh, thresh, thresh))
            }
        }
        gray.recycle()
        scaled.recycle()
        return result
    }

    fun extractNumberFromText(text: String): Double? {
        // Look for patterns like 1934.56, 1,934.56,  1934.5
        val regexes = listOf(
            Regex("""\d{2,5}\.\d{1,5}"""),
            Regex("""\d{1,3},\d{3}\.\d+"""),
            Regex("""\d+\.\d+"""),
            Regex("""\d{3,6}""") // for JPY etc without decimal?
        )
        for (regex in regexes) {
            val match = regex.find(text.replace(" ", "")) ?: continue
            val cleaned = match.value.replace(",", "")
            try {
                return cleaned.toDouble()
            } catch (e: Exception) { continue }
        }
        return null
    }
}
