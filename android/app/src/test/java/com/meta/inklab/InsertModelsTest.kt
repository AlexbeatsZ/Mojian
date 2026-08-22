package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertModelsTest {
    @Test
    fun pageDocumentRoundTripPreservesEmbeddedAndViewOnlyFields() {
        val document = PageInsertDocument("book", "page")
        document.objects +=
            InsertObject.Image(
                id = "image",
                documentX = 12f,
                documentY = 34f,
                width = 200f,
                height = 100f,
                assetName = "asset.png",
                displayName = "原图.png",
                mimeType = "image/png",
                sourceKind = ImageSourceKind.PDF_PAGE,
                viewInverted = true,
                sourceAssetName = "source.pdf",
                sourcePageNumber = 3,
                pageBackground = true,
            )
        document.objects += InsertObject.Table("table", 0f, 0f, 240f, 80f, 2, 3, mutableListOf("a", "b", "c", "d", "e", "f"))

        val restored = PageInsertDocument.fromJson(document.toJson())

        val image = restored.objects[0] as InsertObject.Image
        assertTrue(image.viewInverted)
        assertEquals("source.pdf", image.sourceAssetName)
        assertEquals(3, image.sourcePageNumber)
        assertTrue(image.pageBackground)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), (restored.objects[1] as InsertObject.Table).cells)
    }
}

