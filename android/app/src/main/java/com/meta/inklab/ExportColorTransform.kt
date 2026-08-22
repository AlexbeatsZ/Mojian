package com.meta.inklab

import android.graphics.Bitmap
import kotlin.math.abs

object ExportColorTransform {
    /** Maps dark-theme logical black ink/text (displayed near-white) back to black for light export. */
    fun lightThemePixel(argb: Int): Int {
        val alpha = argb ushr 24 and 0xFF
        if (alpha == 0) return argb
        val red = argb ushr 16 and 0xFF
        val green = argb ushr 8 and 0xFF
        val blue = argb and 0xFF
        val neutral = abs(red - green) <= 18 && abs(green - blue) <= 18 && abs(red - blue) <= 18
        return if (neutral && red >= 205 && green >= 205 && blue >= 205) {
            (alpha shl 24) or (20 shl 16) or (20 shl 8) or 20
        } else {
            argb
        }
    }

    fun applyTo(bitmap: Bitmap) {
        val width = bitmap.width
        val row = IntArray(width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in row.indices) row[x] = lightThemePixel(row[x])
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }
}
