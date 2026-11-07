package com.meta.inklab

import android.annotation.SuppressLint
import android.graphics.Color
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SuppressLint("RestrictedApi")
@OptIn(ExperimentalInkCustomBrushApi::class)
@RunWith(AndroidJUnit4::class)
class BrushStabilityIntegrationTest {
    @Test fun nativeWindowAndSharedMetadataFollowTheSelectedStability() {
        for ((percent, window) in listOf(0 to 1L, 25 to 10L, 50 to 20L, 75 to 60L, 100 to 100L)) {
            val brush = BrushProfiles.create(4f, stabilityPercent = percent)
            assertEquals(window, (brush.family.inputModel as BrushFamily.SlidingWindowModel).windowDurationMillis)
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, 10f, 10f, 0L, pressure = .8f)
                add(InputToolType.STYLUS, 30f, 10f, 20L, pressure = .3f)
            }
            val json = SharedStrokeCodec.encode(Stroke(brush, inputs))
            assertEquals(percent, json.getInt("stabilityPercent"))
            assertEquals(BrushProfiles.FOUNTAIN_V2, json.getString("brushProfile"))
            assertTrue(json.getString("vectorPath").isNotBlank())
            val restored = SharedStrokeCodec.decode(json, { Color.BLACK })
            assertEquals(json.getString("vectorPath"), SharedStrokeCodec.encode(restored).getString("vectorPath"))
        }
    }
}
