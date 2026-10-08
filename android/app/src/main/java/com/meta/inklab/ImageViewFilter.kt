package com.meta.inklab

import kotlin.math.roundToInt

/** CSS-equivalent matrix for invert(89.4118%) hue-rotate(180deg), calibrated to #1b1b1b paper. */
object ImageViewFilter {
    const val TARGET_BACKGROUND_GRAY = 27
    const val INVERT_PERCENT = 89.4118f

    val colorMatrix: FloatArray =
        floatArrayOf(
            0.45244706f, -1.1271765f, -0.113505885f, 0f, 228f,
            -0.33578825f, -0.3389412f, -0.113505885f, 0f, 228f,
            -0.33578825f, -1.1271765f, 0.6747294f, 0f, 228f,
            0f, 0f, 0f, 1f, 0f,
        )

    @Suppress("UNUSED_PARAMETER")
    fun shouldApply(viewInverted: Boolean, forExport: Boolean): Boolean = viewInverted

    fun transform(red: Int, green: Int, blue: Int): IntArray =
        IntArray(3) { row ->
            val offset = row * 5
            (
                colorMatrix[offset] * red +
                    colorMatrix[offset + 1] * green +
                    colorMatrix[offset + 2] * blue +
                    colorMatrix[offset + 4]
            ).roundToInt().coerceIn(0, 255)
        }
}
