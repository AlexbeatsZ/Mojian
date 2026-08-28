package com.meta.inklab

object ClipboardImagePolicy {
    fun isImageMimeType(value: String?): Boolean =
        value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.startsWith("image/") == true
}
