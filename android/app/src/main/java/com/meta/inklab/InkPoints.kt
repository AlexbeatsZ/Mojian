package com.meta.inklab

import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Shared packed stroke samples (`pointsCoded`, shared/ink-points.md): per-channel zigzag LEB128
 * deltas of quantized integers, like AndroidX Ink's CodedNumericRun. The page tree keeps the
 * packed string; callers decode one stroke's points only when they need them, so a dense page no
 * longer holds hundreds of thousands of point JSONObjects.
 */
internal object InkPoints {
    const val PREFIX = "1:"
    const val CODED = "pointsCoded"
    const val POINTS = "points"
    private val names = arrayOf("x", "y", "pressure", "elapsedMilliseconds", "tiltX", "tiltY", "tiltRadians", "orientationRadians")
    private val scales = doubleArrayOf(1000.0, 1000.0, 10000.0, 1.0, 100.0, 100.0, 10000.0, 10000.0)

    /** The stroke's points as objects, decoding `pointsCoded` on demand (never stored back). */
    fun read(stroke: JSONObject): JSONArray {
        stroke.optJSONArray(POINTS)?.let { return it }
        val coded = stroke.optString(CODED)
        return if (coded.isEmpty()) JSONArray() else decode(coded)
    }

    fun count(stroke: JSONObject): Int = stroke.optJSONArray(POINTS)?.length()
        ?: stroke.optString(CODED).takeIf { it.isNotEmpty() }?.let { decode(it).length() } ?: 0

    /** Replaces a plain eligible `points` array by `pointsCoded`; returns [stroke]. */
    fun pack(stroke: JSONObject): JSONObject {
        val points = stroke.optJSONArray(POINTS) ?: return stroke
        encode(points)?.let { stroke.remove(POINTS); stroke.put(CODED, it) }
        return stroke
    }

    private fun quantize(value: Double, scale: Double): Long {
        val scaled = value * scale
        return if (scaled >= 0) Math.floor(scaled + .5).toLong() else -Math.floor(-scaled + .5).toLong()
    }

    /** Null when any point has other fields, mixed optional channels or non-finite values. */
    fun encode(points: JSONArray): String? {
        val count = points.length()
        if (count == 0) return null
        val rows = Array(count) { DoubleArray(names.size) }
        var mask = -1
        for (i in 0 until count) {
            val point = points.opt(i) as? JSONObject ?: return null
            var present = 0
            val keys = point.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val channel = names.indexOf(key)
                if (channel < 0) return null
                val value = point.opt(key) as? Number ?: return null
                val number = value.toDouble()
                if (!number.isFinite() || channel == 3 && number != Math.floor(number)) return null
                rows[i][channel] = number; present = present or (1 shl channel)
            }
            if (present and 3 != 3 || mask >= 0 && present != mask) return null
            mask = present
        }
        val out = java.io.ByteArrayOutputStream(16 + count * 10)
        fun unsigned(v: Long) {
            var value = v
            while (value and 0x7fL.inv() != 0L) { out.write(((value and 0x7f) or 0x80).toInt()); value = value ushr 7 }
            out.write(value.toInt())
        }
        unsigned(count.toLong()); unsigned((mask shr 2).toLong())
        for (channel in names.indices) {
            if (mask and (1 shl channel) == 0) continue
            var previous = 0L
            for (row in rows) {
                val q = quantize(row[channel], scales[channel])
                val delta = q - previous; previous = q
                unsigned((delta shl 1) xor (delta shr 63))
            }
        }
        return PREFIX + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Decoded rows (null where a channel is absent) as point objects with the shared names. */
    fun decode(text: String): JSONArray {
        if (!text.startsWith(PREFIX)) throw JSONException("Unsupported packed points")
        val bytes = try { Base64.getDecoder().decode(text.substring(PREFIX.length)) } catch (e: IllegalArgumentException) { throw JSONException("Corrupt packed points") }
        var offset = 0
        fun unsigned(): Long {
            var result = 0L; var shift = 0
            while (true) {
                if (offset >= bytes.size || shift > 63) throw JSONException("Corrupt packed points")
                val b = bytes[offset++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        val count = unsigned().also { if (it < 0 || it > Int.MAX_VALUE) throw JSONException("Corrupt packed points") }.toInt()
        val mask = (unsigned().toInt() shl 2) or 3
        val values = Array(names.size) { DoubleArray(0) }
        for (channel in names.indices) {
            if (mask and (1 shl channel) == 0) continue
            val column = DoubleArray(count); var value = 0L
            for (i in 0 until count) {
                val z = unsigned()
                value += (z ushr 1) xor -(z and 1)
                column[i] = value / scales[channel]
            }
            values[channel] = column
        }
        if (offset != bytes.size) throw JSONException("Corrupt packed points")
        val result = JSONArray()
        for (i in 0 until count) {
            val point = JSONObject()
            for (channel in names.indices) {
                if (mask and (1 shl channel) == 0) continue
                if (channel == 3) point.put(names[channel], values[channel][i].toLong()) else point.put(names[channel], values[channel][i])
            }
            result.put(point)
        }
        return result
    }
}
