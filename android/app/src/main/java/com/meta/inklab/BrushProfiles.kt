package com.meta.inklab

import android.annotation.SuppressLint
import android.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.EasingFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableVec

// AndroidX Ink 1.0.0 exposes its custom-brush graph as an experimental API while also marking the
// constructors library-group-only for lint. This lab deliberately pins that exact version and
// contains the dependency-sensitive behavior in this one object.
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalInkCustomBrushApi::class)
object BrushProfiles {
    private val slidingInputModel by lazy { BrushFamily.SlidingWindowModel() }

    internal data class FountainRanges(val pressureLow: Float, val pressureHigh: Float, val speedLow: Float, val speedHigh: Float)

    internal fun fountainRanges(pressureCorrect: Float, speedCorrect: Float): FountainRanges {
        val pressureScale = (pressureCorrect / 2f).coerceIn(0f, 2f)
        val speedScale = speedCorrect.coerceIn(0f, 2f)
        return FountainRanges(
            pressureLow = 1f - (1f - 0.55f) * pressureScale,
            pressureHigh = 1f + (1.45f - 1f) * pressureScale,
            speedLow = 1f + 0.08f * speedScale,
            speedHigh = (1f - (1f - 0.82f) * speedScale).coerceAtLeast(0.25f),
        )
    }

    /** AndroidX Ink requires two finite, distinct target-modifier values. */
    internal fun isEffectiveModifierRange(start: Float, end: Float): Boolean =
        start.isFinite() && end.isFinite() && kotlin.math.abs(start - end) > 1e-4f

    /** Stored `brushProfile` of strokes authored with [create]. */
    const val FOUNTAIN_V2 = "fountain-v2"
    /** Stored `brushProfile` of older strokes (and every Windows-authored stroke); see [createLegacy]. */
    const val FOUNTAIN_V1 = "fountain-v1"
    private const val FOUNTAIN_V2_FAMILY_PREFIX = "inklab-fountain-v4-"

    /** Profile written to the shared JSON for a stroke drawn with this family. */
    fun storedProfile(clientBrushFamilyId: String): String =
        if (clientBrushFamilyId.startsWith(FOUNTAIN_V2_FAMILY_PREFIX)) FOUNTAIN_V2 else FOUNTAIN_V1

    /**
     * Width at full pressure and rest, relative to the nominal tool width. Calibrated against Notein
     * 2.1.48 (size 0.5 drew ≈0.71 mm at p=1), so the same number gives the same stroke weight.
     */
    internal const val FULL_PRESSURE_WIDTH = 1.4f
    /** On-glass speed (cm/s) at which speed thinning reaches its cap. */
    internal const val SPEED_CAP_CM_PER_SECOND = 64f
    /** Thinning at [SPEED_CAP_CM_PER_SECOND] with 100% ink flow. */
    internal const val SPEED_THINNING_CAP = 0.85f
    /** Measured Notein thinning g(v) at 100% ink flow, v in cm/s: width × (1 − s·g(v)). */
    private val speedThinning = listOf(1f to 0f, 2f to 0.08f, 4f to 0.15f, 8f to 0.23f, 16f to 0.46f, 32f to 0.69f)

    internal data class FountainV2Spec(
        /** Interior points of the pressure response (x = pressure, y = p^γ); (0,0),(1,1) implied. */
        val pressureCurve: List<Pair<Float, Float>>,
        val pressureMultiplierEnd: Float,
        /** Interior points of g(v)/cap over v/[SPEED_CAP_CM_PER_SECOND]; (0,0),(1,1) implied. */
        val speedCurve: List<Pair<Float, Float>>,
        /** Size multiplier at the speed cap; null when ink flow is 0% (behavior omitted). */
        val speedMultiplierEnd: Float?,
    )

    /**
     * Notein-calibrated fountain response, `width = w · 1.4 · p^γ · (1 − s·g(v)) · dir`, with
     * γ = pressure% / 100 and s = ink-flow% / 100. See docs/design/handwriting-engine.md
     * (2026-11-07). There is deliberately no distance-based start/end taper and no pressure damping:
     * teardrop heads and hairline tails come from the hand's own pressure and speed.
     */
    internal fun fountainV2Spec(pressureCorrect: Float, speedCorrect: Float): FountainV2Spec {
        val gamma = pressureCorrect.coerceIn(0f, 4f)
        val pressureCurve =
            if (gamma < 1e-3f) {
                // p^0 = 1: a discontinuity at x = 0 keeps the width constant for any contact.
                listOf(0f to 1f)
            } else {
                (1 until 16).map { i -> val x = i / 16f; x to Math.pow(x.toDouble(), gamma.toDouble()).toFloat() }
            }
        val speedCurve = speedThinning.map { (v, g) -> v / SPEED_CAP_CM_PER_SECOND to g / SPEED_THINNING_CAP }
        val s = speedCorrect.coerceIn(0f, 2f)
        val speedEnd = (1f - SPEED_THINNING_CAP * s).coerceAtLeast(0.05f)
        return FountainV2Spec(
            pressureCurve = pressureCurve,
            pressureMultiplierEnd = FULL_PRESSURE_WIDTH,
            speedCurve = speedCurve,
            speedMultiplierEnd = speedEnd.takeIf { isEffectiveModifierRange(1f, it) },
        )
    }

    /** Authoring brush: the Notein-calibrated fountain, stored as [FOUNTAIN_V2]. */
    fun create(
        sizePx: Float,
        pressureCorrect: Float = 2f,
        speedCorrect: Float = 1f,
        colorArgb: Int = Color.rgb(16, 19, 23),
    ): Brush {
        val spec = fountainV2Spec(pressureCorrect, speedCorrect)
        val behaviors =
            listOfNotNull(
                StockBrushes.predictionFadeOutBehavior,
                BrushBehavior(
                    source = BrushBehavior.Source.NORMALIZED_PRESSURE,
                    target = BrushBehavior.Target.SIZE_MULTIPLIER,
                    sourceValueRangeStart = 0f,
                    sourceValueRangeEnd = 1f,
                    targetModifierRangeStart = 0f,
                    targetModifierRangeEnd = spec.pressureMultiplierEnd,
                    sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                    responseCurve = EasingFunction.Linear(spec.pressureCurve.map { (x, y) -> ImmutableVec(x, y) }),
                    enabledToolTypes = setOf(InputToolType.STYLUS),
                ),
                spec.speedMultiplierEnd?.let { end ->
                    BrushBehavior(
                        source = BrushBehavior.Source.INPUT_SPEED_IN_CENTIMETERS_PER_SECOND,
                        target = BrushBehavior.Target.SIZE_MULTIPLIER,
                        sourceValueRangeStart = 0f,
                        sourceValueRangeEnd = SPEED_CAP_CM_PER_SECOND,
                        targetModifierRangeStart = 1f,
                        targetModifierRangeEnd = end,
                        sourceOutOfRangeBehavior = BrushBehavior.OutOfRange.CLAMP,
                        responseCurve = EasingFunction.Linear(spec.speedCurve.map { (x, y) -> ImmutableVec(x, y) }),
                        // Notein's speed response settles ≈50 ms after a step, beyond the input window.
                        responseTimeMillis = 25L,
                        enabledToolTypes = setOf(InputToolType.STYLUS),
                    )
                },
                // Downward strokes ≈+9-17% (stock pressure-pen direction response, measured in Notein).
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
            )
        val family =
            BrushFamily(
                tip = BrushTip(behaviors = behaviors),
                clientBrushFamilyId = "${FOUNTAIN_V2_FAMILY_PREFIX}p$pressureCorrect-s$speedCorrect",
                inputModel = slidingInputModel,
            )
        return Brush.createWithColorIntArgb(family = family, colorIntArgb = colorArgb, size = sizePx, epsilon = 0.01f)
    }

    /**
     * The pre-0.7.18 fountain. Strokes stored as [FOUNTAIN_V1] without an outline (all Windows ink and
     * older Android ink) are rebuilt with it so existing notes keep their appearance.
     */
    fun createLegacy(
        sizePx: Float,
        pressureCorrect: Float = 2f,
        speedCorrect: Float = 1f,
        colorArgb: Int = Color.rgb(16, 19, 23),
    ): Brush {
        // Notein persists the displayed percentage as percentage / 100: 200% -> 2.0 and
        // 100% -> 1.0. The lab keeps that exact boundary mapping. Internally, 200% remains the
        // reference pressure response, so 400% doubles its distance from a neutral multiplier.
        val ranges = fountainRanges(pressureCorrect, speedCorrect)
        val family =
            fountainFamily(
                clientId = "inklab-fountain-v3-p${pressureCorrect}-s${speedCorrect}",
                startSize = 0.55f,
                endSize = 0.18f,
                pressureLow = ranges.pressureLow,
                pressureHigh = ranges.pressureHigh,
                speedLow = ranges.speedLow,
                speedHigh = ranges.speedHigh,
            )

        return Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = colorArgb,
            size = sizePx,
            epsilon = 0.01f,
        )
    }

    fun createHighlighter(
        sizePx: Float,
        colorArgb: Int,
        opacityPercent: Int,
    ): Brush {
        val alpha = (opacityPercent.coerceIn(10, 70) / 100f * 255f).toInt()
        val translucentColor =
            Color.argb(alpha, Color.red(colorArgb), Color.green(colorArgb), Color.blue(colorArgb))
        return Brush.createWithColorIntArgb(
            family = StockBrushes.highlighter(),
            colorIntArgb = translucentColor,
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
            listOfNotNull(
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
                // A 0% pressure/speed correction collapses the modifier range to {1, 1}; AndroidX Ink
                // rejects a degenerate range (crashed while dragging the slider), and the behavior
                // would have no effect anyway, so it is omitted.
                if (!isEffectiveModifierRange(pressureLow, pressureHigh)) null else BrushBehavior(
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
                if (!isEffectiveModifierRange(speedLow, speedHigh)) null else BrushBehavior(
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
