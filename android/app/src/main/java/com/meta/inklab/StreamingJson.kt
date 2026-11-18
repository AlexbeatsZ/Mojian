package com.meta.inklab

import java.io.Reader
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Builds the same org.json tree as `JSONObject(reader.readText())` without first materializing the
 * whole document as one String. `readText()` grows a UTF-16 StringBuilder by doubling, so a 130 MB
 * page needed a 537 MB allocation and failed on a 512 MB heap even though the parsed tree fits.
 * Scalars are converted by the platform's own JSONTokener, so numeric types match `JSONObject(String)`.
 */
internal class StreamingJson private constructor(private val reader: Reader) {
    private val buffer = CharArray(64 * 1024)
    private var length = 0
    private var position = 0
    // Reused for every string/literal; grows only to the longest single value (one outline).
    private val text = StringBuilder()

    companion object {
        fun readObject(reader: Reader): JSONObject {
            val parser = StreamingJson(reader)
            val value = parser.value()
            parser.skipWhitespace()
            if (parser.peek() != -1) throw JSONException("Unexpected content after JSON document")
            return value as? JSONObject ?: throw JSONException("Expected a JSON object")
        }
    }

    private fun peek(): Int {
        if (position == length) {
            val read = reader.read(buffer)
            if (read <= 0) { length = 0; position = 0; return -1 }
            length = read; position = 0
        }
        return buffer[position].code
    }

    private fun next(): Char {
        val c = peek()
        if (c < 0) throw JSONException("Unexpected end of JSON")
        position++
        return c.toChar()
    }

    private fun skipWhitespace() {
        while (true) {
            when (peek()) { ' '.code, '\n'.code, '\r'.code, '\t'.code -> position++; else -> return }
        }
    }

    private fun value(): Any {
        skipWhitespace()
        return when (peek()) {
            '{'.code -> { position++; obj() }
            '['.code -> { position++; array() }
            '"'.code -> { position++; string() }
            -1 -> throw JSONException("Unexpected end of JSON")
            else -> literal()
        }
    }

    private fun obj(): JSONObject {
        val result = JSONObject()
        skipWhitespace()
        if (peek() == '}'.code) { position++; return result }
        while (true) {
            skipWhitespace()
            if (next() != '"') throw JSONException("Expected a property name")
            val name = string()
            skipWhitespace()
            if (next() != ':') throw JSONException("Expected ':' after $name")
            result.put(name, value())
            skipWhitespace()
            when (next()) {
                ',' -> continue
                '}' -> return result
                else -> throw JSONException("Expected ',' or '}'")
            }
        }
    }

    private fun array(): JSONArray {
        val result = JSONArray()
        skipWhitespace()
        if (peek() == ']'.code) { position++; return result }
        while (true) {
            result.put(value())
            skipWhitespace()
            when (next()) {
                ',' -> continue
                ']' -> return result
                else -> throw JSONException("Expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        text.setLength(0)
        while (true) {
            // Copy unescaped runs straight from the buffer.
            if (peek() < 0) throw JSONException("Unterminated string")
            var end = position
            while (end < length && buffer[end] != '"' && buffer[end] != '\\') end++
            text.appendRange(buffer, position, end)
            position = end
            if (position == length) continue
            when (next()) {
                '"' -> return text.toString()
                else -> when (val escaped = next()) {
                    '"', '\\', '/' -> text.append(escaped)
                    'b' -> text.append('\b')
                    'f' -> text.append('\u000c')
                    'n' -> text.append('\n')
                    'r' -> text.append('\r')
                    't' -> text.append('\t')
                    'u' -> {
                        var code = 0
                        repeat(4) { code = code * 16 + (Character.digit(next(), 16).takeIf { it >= 0 } ?: throw JSONException("Invalid \\u escape")) }
                        text.append(code.toChar())
                    }
                    else -> throw JSONException("Invalid escape \\$escaped")
                }
            }
        }
    }

    private fun literal(): Any {
        text.setLength(0)
        while (true) {
            val c = peek()
            if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
            text.append(c.toChar()); position++
        }
        if (text.isEmpty()) throw JSONException("Expected a value")
        return when (val token = text.toString()) {
            "true" -> true
            "false" -> false
            "null" -> JSONObject.NULL
            else -> JSONTokener(token).nextValue()
        }
    }
}
