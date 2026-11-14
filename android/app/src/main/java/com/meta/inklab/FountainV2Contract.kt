package com.meta.inklab

import org.json.JSONObject

internal object FountainV2Contract {
    private val source = requireNotNull(javaClass.classLoader?.getResourceAsStream("fountain-v2.json")) {
        "Missing shared fountain-v2 contract"
    }.bufferedReader().use { JSONObject(it.readText()) }
    val fullPressureWidth = source.getDouble("fullPressureWidth").toFloat()
    val pressureSegments = source.getInt("pressureSegments")
    val speedCap = source.getDouble("speedCapCmPerSecond").toFloat()
    val thinningCap = source.getDouble("speedThinningCap").toFloat()
    val minimumSpeedMultiplier = source.getDouble("minimumSpeedMultiplier").toFloat()
    val directionMinimum = source.getDouble("directionMinimum").toFloat()
    val directionMaximum = source.getDouble("directionMaximum").toFloat()
    val directionAmount = source.getDouble("directionAmount").toFloat()
    val responseMilliseconds = source.getLong("responseMilliseconds")
    val stabilityMiddle = source.getDouble("stabilityMiddleMilliseconds").toFloat()
    val stabilityMaximum = source.getDouble("stabilityMaximumMilliseconds").toFloat()
    val upsamplingHz = source.getInt("upsamplingHz")
    val speedCurve = source.getJSONArray("speedCurve").let { points ->
        (0 until points.length()).map { index -> points.getJSONArray(index).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } }
    }
}
