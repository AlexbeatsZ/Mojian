package com.meta.inklab

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialized text of one page's stroke objects, reused across saves of that page.
 *
 * Stroke JSON objects are immutable once created (`SharedStrokeCodec` copies on transform; loaded
 * strokes keep their parsed object), so identity is a valid key. Each write keeps only the strokes
 * still on the page, and caching stops past [limitChars] so dense imported pages are not duplicated
 * in memory. Output bytes are identical with or without the cache.
 */
internal class StrokeTextCache(private val limitChars: Long = 24_000_000L) {
    private var entries = java.util.IdentityHashMap<JSONObject,String>()
    internal var hits = 0; private set
    internal var misses = 0; private set

    internal fun write(stroke: JSONObject, writer: Writer, next: java.util.IdentityHashMap<JSONObject,String>, budget: LongArray) {
        val text = entries[stroke]?.also { hits++ } ?: java.io.StringWriter().also { WorkspaceJson.write(stroke,it) }.toString().also { misses++ }
        if (budget[0] + text.length <= limitChars) { next[stroke] = text; budget[0] += text.length }
        writer.write(text)
    }

    internal fun begin() { hits = 0; misses = 0 }
    internal fun replace(next: java.util.IdentityHashMap<JSONObject,String>) { entries = next }
}

/** Stream large canonical pages without allocating a second whole-document string. */
internal object WorkspaceJson {
    private const val STROKES = "strokes"
    // 32-byte typed digests per immutable stroke object; negligible memory even for dense pages.
    private val strokeDigests = WeakIdentityCache<JSONObject,ByteArray>()

    fun write(value: Any?, writer: Writer) = write(value, writer, null)

    /** Writes a page; with [cache], unchanged top-level strokes reuse their serialized text. */
    fun write(value: Any?, writer: Writer, cache: StrokeTextCache?) {
        when(value) {
            null, JSONObject.NULL -> writer.write("null")
            is JSONObject -> {
                writer.write("{")
                var first=true
                value.keys().forEach { key ->
                    if(!first) writer.write(",")
                    first=false
                    writer.write(JSONObject.quote(key));writer.write(":")
                    val item=value.get(key)
                    if(cache!=null && key==STROKES && item is JSONArray) writeStrokes(item,writer,cache) else write(item,writer,null)
                }
                writer.write("}")
            }
            is JSONArray -> {
                writer.write("[")
                for(index in 0 until value.length()) {
                    if(index>0) writer.write(",")
                    write(value.get(index),writer,null)
                }
                writer.write("]")
            }
            is Number -> writer.write(JSONObject.numberToString(value))
            is Boolean -> writer.write(value.toString())
            else -> writer.write(JSONObject.quote(value.toString()))
        }
    }

    private fun writeStrokes(strokes: JSONArray, writer: Writer, cache: StrokeTextCache) {
        cache.begin()
        val next=java.util.IdentityHashMap<JSONObject,String>()
        val budget=longArrayOf(0)
        writer.write("[")
        for(index in 0 until strokes.length()) {
            if(index>0) writer.write(",")
            val stroke=strokes.get(index)
            if(stroke is JSONObject) cache.write(stroke,writer,next,budget) else write(stroke,writer,null)
        }
        writer.write("]")
        cache.replace(next)
    }

    fun fingerprint(value: JSONObject): String {
        val digest=MessageDigest.getInstance("SHA-256")
        val sink=object : OutputStream() { override fun write(value: Int) = Unit;override fun write(bytes: ByteArray,offset: Int,length: Int) = Unit }
        // Content comparison needs stable typed values, not another JSON text encoding. This
        // avoids quoting long outline strings and decimal-formatting millions of coordinates
        // merely to identify an unchanged imported page.
        val output=java.io.DataOutputStream(java.io.BufferedOutputStream(DigestOutputStream(sink,digest)))
        fun string(text: String) {
            val bytes=text.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size);output.write(bytes)
        }
        fun visit(item: Any?, topLevel: Boolean = false) {
            when(item) {
                null, JSONObject.NULL -> output.writeByte(0)
                is JSONObject -> {
                    output.writeByte(1);output.writeInt(item.length())
                    item.keys().forEach { key ->
                        string(key)
                        val child=item.get(key)
                        if(topLevel && key==STROKES && child is JSONArray) {
                            output.writeByte(2);output.writeInt(child.length())
                            for(index in 0 until child.length()) {
                                val stroke=child.get(index)
                                if(stroke is JSONObject) { output.writeByte(6);output.write(strokeDigest(stroke)) } else visit(stroke)
                            }
                        } else visit(child)
                    }
                }
                is JSONArray -> {
                    output.writeByte(2);output.writeInt(item.length())
                    for(index in 0 until item.length()) visit(item.get(index))
                }
                is Number -> { output.writeByte(3);output.writeLong(item.toDouble().toBits()) }
                is Boolean -> { output.writeByte(4);output.writeBoolean(item) }
                else -> { output.writeByte(5);string(item.toString()) }
            }
        }
        output.use { visit(value, topLevel = true) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Typed fingerprint digest of one immutable stroke object, cached by identity. */
    private fun strokeDigest(stroke: JSONObject): ByteArray = strokeDigests[stroke]
        ?: fingerprint(stroke).chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { strokeDigests[stroke]=it }
}
