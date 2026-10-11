package com.meta.inklab

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Stream large canonical pages without allocating a second whole-document string. */
internal object WorkspaceJson {
    fun write(value: Any?, writer: Writer) {
        when(value) {
            null, JSONObject.NULL -> writer.write("null")
            is JSONObject -> {
                writer.write("{")
                var first=true
                value.keys().forEach { key ->
                    if(!first) writer.write(",")
                    first=false
                    writer.write(JSONObject.quote(key));writer.write(":");write(value.get(key),writer)
                }
                writer.write("}")
            }
            is JSONArray -> {
                writer.write("[")
                for(index in 0 until value.length()) {
                    if(index>0) writer.write(",")
                    write(value.get(index),writer)
                }
                writer.write("]")
            }
            is Number -> writer.write(JSONObject.numberToString(value))
            is Boolean -> writer.write(value.toString())
            else -> writer.write(JSONObject.quote(value.toString()))
        }
    }

    fun fingerprint(value: JSONObject): String {
        val digest=MessageDigest.getInstance("SHA-256")
        val sink=object : OutputStream() { override fun write(value: Int) = Unit;override fun write(bytes: ByteArray,offset: Int,length: Int) = Unit }
        OutputStreamWriter(DigestOutputStream(sink,digest),Charsets.UTF_8).use { write(value,it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
