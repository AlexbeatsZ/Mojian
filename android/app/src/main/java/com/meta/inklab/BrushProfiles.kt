package com.meta.inklab

import android.annotation.SuppressLint
import android.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes

// AndroidX Ink 1.0.0 exposes its custom-brush graph as an experimental API while also marking the
// constructors library-group-only for lint. This lab deliberately pins that exact version and
// contains the dependency-sensitive behavior in this one object.
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalInkCustomBrushApi::class)
object BrushProfiles {
    private val slidingInputModel by lazy { BrushFamily.SlidingWindowModel() }

    fun create(
        sizePx: Float,
        pressureCorrect: Float = 2f,
        speedCorrect: Float = 1f,
    ): Brush {
        // Notein persists the displayed percentage as percentage / 100: 200% -> 2.0 and
        // 100% -> 1.0. The lab keeps that exact boundary mapping. Internally, 200% remains the
        // reference pressure response, so 400% doubles its distance from a neutral multiplier.
        val pressureScale = (pressureCorrect / 2f).coerceIn(0f, 2f)
        val speedScale = speedCorrect.coerceIn(0f, 2f)
        fun pressureLow(base: Float) = 1f - (1f - base) * pressureScale
        fun pressureHigh(base: Float) = 1f + (base - 1f) * pressureScale
        fun speedHigh(base: Float) = (1f - (1f - base) * speedScale).coerceAtLeast(0.25f)

        val family =
            fountainFamily(
                clientId = "inklab-fountain-v3-p${pressureCorrect}-s${speedCorrect}",
                startSize = 0.55f,
                endSize = 0.18f,
                pressureLow = pressureLow(0.55f),
                pressureHigh = pressureHigh(1.45f),
                speedLow = 1f + 0.08f * speedScale,
                speedHigh = speedHigh(0.82f),
            )

        return Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = Color.rgb(16, 19, 23),
            size = sizePx,
            epsilon = 0.01f,
        )
    }

    /**
     * Clean-room behavior graph inspired by observed Notein metadata, built only from public
     * AndroidX Ink APIs. It is deliberately versioned as a hypothesis rather than called a clone.
     */
    private fun fountainFamily(
        clientId: String,
        startSize: Float,
        endSize: Float,
        pressureLow: Float,
        pressureHigh: Float,
        speedLow: Float,
        speedHigh: Float,
    ): BrushFamily {
        val behaviors =
            listOf(
                // Fade predicted geometry so prediction reduces nib separation without leaving a
                // dark false tail. This is the same public stock behavior used by pressurePen V1.
                StockBrushes.predictionFadeOutBehavior,
                BrushBehavior(
                    source = BrushBehavior.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 0f,
                    sourceValueRangeEnd = 1.25f,
                    targetModifierRangeStart = startSize,
                    targetModifierRangeEnd = 1f,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    responseTimeMillis = 12L,
                ),
                BrushBehavior(
                    source = BrushBehavior.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 2.5f,
                    sourceValueRangeEnd = 0f,
                    targetModifierRangeStart = 1f,
                    targetModifierRangeEnd = endSize,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    responseTimeMillis = 12L,
                ),
                BrushBehavior(
                    source = BrushBehavior.Source.NORMALIZED_PRESSURE,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 0.18f,
                    sourceValueRangeEnd = 0.88f,
                    targetModifierRangeStart = pressureLow,
                    targetModifierRangeEnd = pressureHigh,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    responseTimeMillis = 20L,
                    enabledToolTypes = setOf(InputToolType.STYLUS),
                ),
                BrushBehavior(
                    source = BrushBehavior.Source.INPUT_SPEED_IN_CENTIMETERS_PER_SECOND,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 0f,
                    sourceValueRangeEnd = 85f,
                    targetModifierRangeStart = speedLow,
                    targetModifierRangeEnd = speedHigh,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    responseTimeMillis = 18L,
                    enabledToolTypes = setOf(InputToolType.STYLUS),
                ),
                // Retain the subtle direction and turn response that makes the stock pressure pen
                // feel less like a pressure-scaled round polyline.
                BrushBehavior(
                    source = BrushBehavior.Source.NORMALIZED_DIRECTION_Y,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 0.45f,
                    sourceValueRangeEnd = 0.65f,
                    targetModifierRangeStart = 1f,
                    targetModifierRangeEnd = 1.17f,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    responseTimeMillis = 25L,
                ),
                BrushBehavior(
                    source =
                        BrushBehavior.Source
                            .INPUT_ACCELERATION_LATERAL_IN_CENTIMETERS_PER_SECOND_SQUARED,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = -80f,
                    sourceValueRangeEnd = -230f,
                    targetModifierRangeStart = 1f,
                    targetModifierRangeEnd = 1.25f,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    responseTimeMillis = 25L,
                ),
            )

        return BrushFamily(
            tip = BrushTip(behaviors = behaviors),
            clientBrushFamilyId = clientId,
            inputModel = slidingInputModel,
        )
    }
}
