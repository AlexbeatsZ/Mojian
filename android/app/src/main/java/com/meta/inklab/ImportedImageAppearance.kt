package com.meta.inklab

import android.graphics.Bitmap

object ImportedImageAppearance {
    fun adapt(source: Bitmap, dark: Boolean): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val step = maxOf(1, pixels.size / 16384)
        val samples = IntArray((pixels.size + step - 1) / step) { pixels[it * step] }
        val background = ImportedPagePresentation.imageBackground(samples) ?: return source
        val palette = mutableMapOf<Int, Int>()
        pixels.indices.forEach { index ->
            val original = pixels[index]
            pixels[index] = palette[original] ?: ImportedPagePresentation.imagePixel(original, background, dark).also {
                if (palette.size < 4096) palette[original] = it
            }
        }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    }
}
