package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

data class CurvePoint(val x: Double, val y: Double)

/** Complete mark behavior; a finished stroke owns a snapshot, never a mutable preset reference. */
data class PenStyle(
    val version: Int = 1,
    var pressureEnabled: Boolean = true,
    var pressureCurve: List<CurvePoint> = emptyList(),
    var speedEnabled: Boolean = true,
    var speedCurve: List<CurvePoint> = emptyList(),
    var directionEnabled: Boolean = true,
    var directionAmount: Double = .17,
    var startEnabled: Boolean = false,
    var startLengthMm: Double = .5,
    var startMinimum: Double = .55,
    var endEnabled: Boolean = false,
    var endLengthMm: Double = 1.0,
    var endMinimum: Double = .18,
    var stabilityPercent: Int = 50,
    var tiltEnabled: Boolean = false,
    var tiltWidth: Double = 1.5,
    var turnEnabled: Boolean = false,
    var turnAmount: Double = .25,
    var pressureResponseMs: Int = 0,
    var speedResponseMs: Int = 25,
    var directionResponseMs: Int = 25,
) {
    fun snapshot() = copy(pressureCurve = pressureCurve.toList(), speedCurve = speedCurve.toList())
    fun sameParameters(other: PenStyle): Boolean {
        fun equal(a: List<CurvePoint>, b: List<CurvePoint>) = a.size == b.size && a.zip(b).all { (p, q) -> kotlin.math.abs(p.x - q.x) < 1e-9 && kotlin.math.abs(p.y - q.y) < 1e-9 }
        return copy(pressureCurve = emptyList(), speedCurve = emptyList()) == other.copy(pressureCurve = emptyList(), speedCurve = emptyList()) &&
            equal(pressureCurve, other.pressureCurve) && equal(speedCurve, other.speedCurve)
    }
    fun toJson() = JSONObject().apply {
        put("version", version)
        put("pressureEnabled", pressureEnabled); put("speedEnabled", speedEnabled)
        put("pressureCurve", curveJson(pressureCurve)); put("speedCurve", curveJson(speedCurve))
        put("directionEnabled", directionEnabled); put("directionAmount", directionAmount)
        put("startEnabled", startEnabled); put("startLengthMm", startLengthMm); put("startMinimum", startMinimum)
        put("endEnabled", endEnabled); put("endLengthMm", endLengthMm); put("endMinimum", endMinimum)
        put("stabilityPercent", stabilityPercent)
        put("tiltEnabled", tiltEnabled); put("tiltWidth", tiltWidth)
        put("turnEnabled", turnEnabled); put("turnAmount", turnAmount)
        put("pressureResponseMs", pressureResponseMs); put("speedResponseMs", speedResponseMs); put("directionResponseMs", directionResponseMs)
    }

    companion object {
        const val PROFILE = "pen-style-v1"
        fun fromLegacy(pressure: Int = 200, flow: Int = 100, stability: Int = 50): PenStyle {
            val gamma = (pressure / 100.0).coerceIn(0.0, 4.0)
            val end = (1 - .85 * (flow / 100.0).coerceIn(0.0, 2.0)).coerceAtLeast(.05)
            return PenStyle(
                pressureCurve = (0..16).map { CurvePoint(it / 16.0, if (gamma == 0.0) 1.4 else 1.4 * (it / 16.0).pow(gamma)) },
                speedCurve = listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to .08, 4.0 to .15, 8.0 to .23, 16.0 to .46, 32.0 to .69, 64.0 to .85)
                    .map { (v, g) -> CurvePoint(v, 1 - (1 - end) * g / .85) },
                stabilityPercent = stability.coerceIn(0, 100),
            )
        }
        fun evaluate(curve: List<CurvePoint>, x: Double): Double {
            if (curve.isEmpty()) return 1.0
            if (x <= curve.first().x) return curve.first().y
            for (i in 1 until curve.size) {
                val a = curve[i - 1]; val b = curve[i]
                if (x <= b.x) return a.y + (b.y - a.y) * ((x - a.x) / (b.x - a.x).coerceAtLeast(1e-8)).coerceIn(0.0, 1.0)
            }
            return curve.last().y
        }
        private fun curveJson(curve: List<CurvePoint>) = JSONArray().apply { curve.forEach { put(JSONObject().put("x", it.x).put("y", it.y)) } }
        private fun readCurve(json: JSONArray?, end: Double): List<CurvePoint> {
            require(json != null && json.length() in 2..33)
            return (0 until json.length()).map { i -> json.getJSONObject(i).let { CurvePoint(it.getDouble("x"), it.getDouble("y")) } }.also { curve ->
                require(curve.first().x == 0.0 && curve.last().x == end)
                require(curve.all { it.x.isFinite() && it.x in 0.0..end && it.y.isFinite() && it.y in 0.0..3.0 })
                require(curve.zipWithNext().all { (a, b) -> a.x < b.x })
            }
        }
        fun fromJson(j: JSONObject?): PenStyle? = runCatching {
            require(j != null && j.optInt("version", 1) == 1)
            fun number(key: String, default: Double, min: Double, max: Double) = j.optDouble(key, default).also { require(it.isFinite() && it in min..max) }
            fun time(key: String, default: Int) = j.optInt(key, default).also { require(it in 0..100) }
            PenStyle(
                pressureEnabled = j.optBoolean("pressureEnabled", true), pressureCurve = readCurve(j.optJSONArray("pressureCurve"), 1.0),
                speedEnabled = j.optBoolean("speedEnabled", true), speedCurve = readCurve(j.optJSONArray("speedCurve"), 64.0),
                directionEnabled = j.optBoolean("directionEnabled", true), directionAmount = number("directionAmount", .17, 0.0, 1.0),
                startEnabled = j.optBoolean("startEnabled"), startLengthMm = number("startLengthMm", .5, .1, 20.0), startMinimum = number("startMinimum", .55, 0.0, 1.0),
                endEnabled = j.optBoolean("endEnabled"), endLengthMm = number("endLengthMm", 1.0, .1, 20.0), endMinimum = number("endMinimum", .18, 0.0, 1.0),
                stabilityPercent = time("stabilityPercent", 50),
                tiltEnabled = j.optBoolean("tiltEnabled"), tiltWidth = number("tiltWidth", 1.5, .1, 3.0),
                turnEnabled = j.optBoolean("turnEnabled"), turnAmount = number("turnAmount", .25, 0.0, 1.0),
                pressureResponseMs = time("pressureResponseMs", 0), speedResponseMs = time("speedResponseMs", 25), directionResponseMs = time("directionResponseMs", 25),
            )
        }.getOrNull()
    }
}

data class NamedPenStyle(val id: String, val name: String, val style: PenStyle) {
    override fun toString() = name
    fun toJson() = JSONObject().put("id", id).put("name", name).put("style", style.toJson())
    companion object {
        fun fromJson(j: JSONObject?): NamedPenStyle? = runCatching {
            require(j != null)
            val id = j.getString("id"); val name = j.getString("name")
            require(id.isNotBlank() && name.isNotBlank() && name.length <= 40)
            NamedPenStyle(id, name, requireNotNull(PenStyle.fromJson(j.optJSONObject("style"))))
        }.getOrNull()
        fun defaults(): List<NamedPenStyle> {
            val source = requireNotNull(NamedPenStyle::class.java.getResourceAsStream("/pen-style-presets.json")).bufferedReader().use { it.readText() }
            val values = JSONArray(source)
            return (0 until values.length()).map { requireNotNull(fromJson(values.getJSONObject(it))) }
        }
    }
}
