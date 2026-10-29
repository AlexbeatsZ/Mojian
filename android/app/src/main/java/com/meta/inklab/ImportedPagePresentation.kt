package com.meta.inklab

import kotlin.math.pow
import kotlin.math.roundToInt

object ImportedPagePresentation {
    fun applies(pageId: String?) = pageId?.let { it.startsWith("page-notein-") || it.startsWith("page-onenote-") } == true
    fun importedItem(id: String) = id.contains("-notein-") || id.contains("-onenote-")
    fun imageInverted(settings: PageViewSettings, id: String, stored: Boolean) =
        stored xor (settings.invertImportedImages && importedItem(id))

    fun display(source: PageViewSettings, pageId: String?, content: Sequence<StrokeViewportBounds> = emptySequence()): PageViewSettings {
        if (!applies(pageId)) return source.copy()
        val result = source.copy(pageColor = PageColor.WHITE, importedPresentation = true,
            invertImportedImages = isDark(source.sourcePageColor ?: source.pageColor.displayArgb(false)) != source.darkBackground)
        if (source.paperSize == PaperSize.PDF) return result
        var left = 0f
        var top = 0f
        var right = source.documentWidth ?: A4PageLayout.pageWidth
        var bottom = if (source.paperSize == PaperSize.A4) A4PageLayout.pageBottom(source.a4PageCount - 1,
            source.documentHeight ?: A4PageLayout.pageHeight) else source.documentHeight ?: A4PageLayout.pageHeight
        val padding = 6f * DOCUMENT_UNITS_PER_MM
        content.forEach { bounds ->
            if (listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all(Float::isFinite)) {
                left = minOf(left, bounds.left - padding)
                top = minOf(top, bounds.top - padding)
                right = maxOf(right, bounds.right + padding)
                bottom = maxOf(bottom, bounds.bottom + padding)
            }
        }
        return result.copy(paperSize = PaperSize.BOUNDLESS, paperOriginX = left, paperOriginY = top,
            boundlessDocumentWidth = right - left, boundlessDocumentHeight = bottom - top,
            customDocumentWidth = null, customDocumentHeight = null)
    }

    fun isDark(color: Int) = luminance(color) < .5

    fun imageBackground(samples: IntArray): Int? {
        val histogram = mutableMapOf<Int, Int>()
        var count = 0
        samples.forEach { color ->
            if (color ushr 24 >= 230) {
                val key = color and 0xf8f8f8
                histogram[key] = (histogram[key] ?: 0) + 1
                count++
            }
        }
        val primary = histogram.maxByOrNull { it.value } ?: return null
        return if (primary.value >= count * .3) primary.key or 0x040404 else null
    }

    fun imagePixel(source: Int, background: Int, dark: Boolean): Int {
        val red = (source ushr 16 and 255) - (background ushr 16 and 255)
        val green = (source ushr 8 and 255) - (background ushr 8 and 255)
        val blue = (source and 255) - (background and 255)
        if (red * red + green * green + blue * blue <= 2025)
            return (source and -0x1000000) or (PageColor.WHITE.displayArgb(dark) and 0xffffff)
        val channels = listOf(source ushr 16 and 255, source ushr 8 and 255, source and 255)
        val neutral = channels.max() - channels.min() <= 24
        val inverted = neutral && if (dark) channels.sum() < 384 else channels.sum() >= 384
        return ink(source, dark, inverted)
    }

    fun ink(source: Int, dark: Boolean, inverted: Boolean): Int {
        val alpha = source and -0x1000000
        var red = source ushr 16 and 255
        var green = source ushr 8 and 255
        var blue = source and 255
        val neutral = maxOf(red, green, blue) - minOf(red, green, blue) <= 24
        if (neutral && inverted) {
            val value = 255 - (red + green + blue) / 3
            red = value; green = value; blue = value
        }
        val base = alpha or (red shl 16) or (green shl 8) or blue
        val paper = PageColor.WHITE.displayArgb(dark)
        if (neutral && if (dark) luminance(base) < .02 else luminance(base) > .9) return alpha or (paper and 0xffffff)
        val background = luminance(paper)
        val target = if (dark) 255 else 0
        for (step in 0..100) {
            val amount = step / 100.0
            val candidate = alpha or ((red + (target - red) * amount).roundToInt() shl 16) or
                ((green + (target - green) * amount).roundToInt() shl 8) or (blue + (target - blue) * amount).roundToInt()
            val value = luminance(candidate)
            if ((maxOf(value, background) + .05) / (minOf(value, background) + .05) >= 4.5) return candidate
        }
        return base
    }

    private fun luminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(color ushr 16 and 255) + .7152 * linear(color ushr 8 and 255) + .0722 * linear(color and 255)
    }
}
