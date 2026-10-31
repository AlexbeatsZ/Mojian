package com.meta.inklab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageCropTest {
    private fun image() = InsertObject.Image("image", 100f, 200f, 400f, 240f, "original.png", "original.png", "image/png", ImageSourceKind.IMAGE)

    @Test fun cropKeepsDisplayPositionAcrossAllRotationsAndFlipsAndRestoresFullImage() {
        for (turns in 0..3) for (flipX in listOf(false, true)) for (flipY in listOf(false, true)) {
            val item = image().apply { rotationQuarterTurns = turns; flipHorizontal = flipX; flipVertical = flipY }
            val next = ImageCrop(.125, .25, .75, .875)
            assertTrue(ImageCrop.apply(item, next))
            assertEquals(150f, item.documentX); assertEquals(260f, item.documentY)
            assertEquals(250f, item.width); assertEquals(150f, item.height)
            assertEquals(next, ImageCrop.display(item))
            val restored = InsertObject.fromJson(item.toJson()) as InsertObject.Image
            assertEquals(ImageCrop.source(item), ImageCrop.source(restored))
            val shared = SharedWorkspaceCodec.encodeObject(item, null)
            assertEquals(ImageCrop.source(item), ImageCrop.source(SharedWorkspaceCodec.decodeObject(shared) as InsertObject.Image))
            assertTrue(ImageCrop.apply(item, ImageCrop(.25, .375, .5, .625)))
            assertEquals(200f, item.documentX); assertEquals(290f, item.documentY)
            assertTrue(ImageCrop.apply(item, ImageCrop()))
            assertEquals(100f, item.documentX); assertEquals(200f, item.documentY)
            assertEquals(400f, item.width); assertEquals(240f, item.height)
            assertEquals("original.png", item.assetName)
            assertFalse(ImageCrop.apply(item, ImageCrop()))
        }
        val rotated = image().apply { rotationQuarterTurns = 1 }
        ImageCrop.apply(rotated, ImageCrop(0.0, 0.0, .5, 1.0))
        assertEquals(ImageCrop(0.0, .5, 1.0, 1.0), ImageCrop.source(rotated))
    }

    @Test fun missingInvalidAndReversedCropAreFullImage() {
        assertEquals(ImageCrop(), ImageCrop(Double.NaN, 0.0, 1.0, 1.0).normalized())
        assertEquals(ImageCrop(), ImageCrop(.9, 0.0, .1, 1.0).normalized())
        val legacy = image().toJson().apply { listOf("cropLeft", "cropTop", "cropRight", "cropBottom").forEach { remove(it) } }
        assertEquals(ImageCrop(), ImageCrop.source(InsertObject.fromJson(legacy) as InsertObject.Image))
        val original = JSONObject(SharedWorkspaceCodec.encodeObject(image(), null).toString()).put("unknownField", "retained")
        val edited = SharedWorkspaceCodec.decodeObject(original) as InsertObject.Image
        ImageCrop.apply(edited, ImageCrop(.1, .2, .8, .9))
        assertEquals("retained", SharedWorkspaceCodec.encodeObject(edited, original).getString("unknownField"))
    }

    @Test fun cropHandlesShrinkWithoutScalingOrExpandingAndMatchDocumentCoordinates() {
        val original = DocumentRect(100f, 200f, 500f, 440f)
        assertEquals(0, ImageCrop.hitHandle(DocumentPoint(100f, 200f), original, 12f))
        assertEquals(7, ImageCrop.hitHandle(DocumentPoint(101f, 260f), original, 12f))
        assertEquals(-1, ImageCrop.hitHandle(DocumentPoint(300f, 320f), original, 12f))
        val resized = ImageCrop.resizeBounds(original, 0, DocumentPoint(50f, 30f), 12f)
        assertEquals(DocumentRect(150f, 230f, 500f, 440f), resized)
        assertEquals(original, ImageCrop.resizeBounds(original, 0, DocumentPoint(-90f, -90f), 12f))
        val item = image()
        assertTrue(ImageCrop.apply(item, ImageCrop.fromDocumentBounds(item, resized)))
        assertEquals(150f, item.documentX); assertEquals(230f, item.documentY)
        assertEquals(350f, item.width); assertEquals(210f, item.height)
    }

    @Test fun cropAndInkUndoFollowOneChronologicalHistoryAndNewEditsDiscardRedo() {
        val history = ReversibleEditHistory()
        var cropped = true
        var ink = true
        history.record(ReversibleEditHistory.Edit({ cropped = false }, { cropped = true }))
        history.record(ReversibleEditHistory.Edit({ ink = false }, { ink = true }))
        assertTrue(history.undo()); assertTrue(cropped); assertFalse(ink)
        assertTrue(history.undo()); assertFalse(cropped)
        assertTrue(history.redo()); assertTrue(cropped); assertFalse(ink)
        history.record(ReversibleEditHistory.Edit({}, {}))
        assertFalse(history.redo())
        history.clear(); assertFalse(history.undo())
    }
}
