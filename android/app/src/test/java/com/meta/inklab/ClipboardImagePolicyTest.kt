package com.meta.inklab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardImagePolicyTest {
    @Test
    fun imageMimeTypesAreRecognizedWithoutTreatingTextAsAnImage() {
        assertTrue(ClipboardImagePolicy.isImageMimeType("image/png"))
        assertTrue(ClipboardImagePolicy.isImageMimeType("IMAGE/JPEG; charset=binary"))
        assertFalse(ClipboardImagePolicy.isImageMimeType("text/html"))
        assertFalse(ClipboardImagePolicy.isImageMimeType(null))
    }
}
