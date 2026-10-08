package com.meta.inklab

/** Output-only appearance; assets, page state and live editors are never changed. */
data class OutputAppearance(val printing: Boolean = false, val invertedImageIds: Set<String>? = null, val hideBackgroundFormat: Boolean = false) {
    fun printsDark(darkPage: Boolean): Boolean = printing && darkPage
    fun imageInverted(id: String, displayedInverted: Boolean, darkPage: Boolean): Boolean =
        displayedInverted xor (printsDark(darkPage) && (invertedImageIds?.contains(id) ?: displayedInverted))

    companion object {
        fun printInk(argb: Int): Int {
            val r = argb ushr 16 and 255
            val g = argb ushr 8 and 255
            val b = argb and 255
            return if (r == g && g == b && (r == 239 || r == 244 || r == 255)) argb and -0x1000000 else argb
        }
        fun printPaper(argb: Int): Int {
            val r = argb ushr 16 and 255
            val g = argb ushr 8 and 255
            val b = argb and 255
            return if (r == g && g == b && r <= 32) (argb and -0x1000000) or 0xFFFFFF else argb
        }
    }
}

object ImageImportPolicy {
    fun resolve(mode: ImageImportDisplayMode, darkPage: Boolean): Boolean? = if (!darkPage) false else when (mode) {
        ImageImportDisplayMode.INVERT -> true
        ImageImportDisplayMode.ORIGINAL -> false
        ImageImportDisplayMode.ASK -> null
    }
}
