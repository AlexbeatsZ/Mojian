package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class SelectionClipboardTest {
    @Test
    fun nativeObjectPasteGetsIndependentIdentityTransformAndOwnedAssetWithoutChangingSource() {
        val source = InsertObject.Image("image", 10f, 20f, 50f, 60f, "preview.png", "PDF", "image/png",
            ImageSourceKind.PDF_PAGE, sourceAssetName = "source.pdf", sourcePageNumber = 3,
            pageBackground = true, rotationQuarterTurns = 1, flipHorizontal = true)
        val copy = SelectionClipboard.translatedObject(source, 18f, mapOf("preview.png" to "new.png", "source.pdf" to "new.pdf")) as InsertObject.Image
        assertNotEquals(source.id, copy.id)
        assertEquals(28f, copy.documentX); assertEquals(38f, copy.documentY)
        assertEquals("new.png", copy.assetName); assertEquals("new.pdf", copy.sourceAssetName)
        assertEquals(3, copy.sourcePageNumber); assertEquals(1, copy.rotationQuarterTurns); assertTrue(copy.flipHorizontal)
        assertFalse(copy.pageBackground); assertEquals("preview.png", source.assetName); assertTrue(source.pageBackground)
        assertEquals(listOf("preview.png", "source.pdf"), SelectionClipboard.assetNames(source))
    }
}
