package com.meta.inklab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageCropIntegrationTest {
    @Test fun actualRendererAndUndoKeepCropOrientationAndOriginalAssetAcrossPageReload() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val root = File(context.cacheDir, ".agents/crop-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val repository = PageContentRepository(root)
                val directory = repository.assetDirectory("book", "page").apply { mkdirs() }
                val asset = File(directory, "quadrants.png")
                val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
                for (y in 0..39) for (x in 0..79) bitmap.setPixel(x, y, if (y < 20) if (x < 40) Color.RED else Color.BLUE else if (x < 40) Color.GREEN else Color.YELLOW)
                asset.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                val originalBytes = asset.readBytes()
                for (turns in 0..3) for (flipX in listOf(false, true)) for (flipY in listOf(false, true)) {
                    val document = PageInsertDocument("book", "page")
                    val image = InsertObject.Image("image", 0f, 0f, 80f, 80f, "quadrants.png", "quadrants.png", "image/png",
                        ImageSourceKind.STICKER, rotationQuarterTurns = turns, flipHorizontal = flipX, flipVertical = flipY)
                    document.objects.add(image)
                    val layer = InsertContentLayer(context, ViewportState(), repository)
                    val ink = DryInkView(context)
                    layer.onReversibleEdit = { undo, redo -> ink.recordExternalEdit(undo, redo) }
                    layer.bind(document)
                    fun render(): Bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).also {
                        layer.renderForExport(Canvas(it), Matrix())
                    }
                    val before = render()
                    assertTrue(layer.cropImage(image, ImageCrop(0.0, 0.0, .5, .5)))
                    val after = render()
                    assertEquals(before.getPixel(10, 10), after.getPixel(10, 10))
                    assertEquals(0, after.getPixel(60, 60))
                    assertTrue(ink.undo())
                    assertEquals(ImageCrop(), ImageCrop.source(document.objects.single() as InsertObject.Image))
                    assertTrue(ink.redo())
                    val cropped = document.objects.single() as InsertObject.Image
                    assertEquals(40f, cropped.width)
                    repository.save(document)
                    val restored = PageContentRepository(root).load("book", "page").objects.single() as InsertObject.Image
                    assertEquals(ImageCrop.source(cropped), ImageCrop.source(restored))
                    before.recycle(); after.recycle()
                    // Rebiding clears closures belonging to the previous page in the real ink owner.
                    ink.replaceStrokes(emptyList())
                }
                val image = InsertObject.Image("gesture", 100f, 100f, 400f, 200f, "quadrants.png", "image", "image/png", ImageSourceKind.STICKER)
                val document = PageInsertDocument("book", "page", mutableListOf(image))
                val layer = InsertContentLayer(context, ViewportState(), repository).apply { bind(document) }
                var edits = 0
                layer.onReversibleEdit = { _, _ -> edits++ }
                assertTrue(layer.beginImageCrop(image))
                val overlay = SelectionOverlayView(context, ViewportState()).apply {
                    selectionBoundsProvider = layer::selectedBounds
                    cropBoundsProvider = layer::imageCropBounds
                    onPreviewCrop = layer::previewImageCrop
                    onCommitCrop = layer::commitImageCrop
                    onExitCrop = { layer.endImageCrop() }
                }
                var time = android.os.SystemClock.uptimeMillis()
                fun pointer(action: Int, x: Float, y: Float) {
                    val event = MotionEvent.obtain(time, time + 10, action, x, y, 0)
                    assertTrue(overlay.onTouchEvent(event)); event.recycle(); time += 10
                }
                pointer(MotionEvent.ACTION_DOWN, 100f, 100f)
                pointer(MotionEvent.ACTION_MOVE, 150f, 130f)
                assertEquals(400f, image.width); assertEquals(0, edits)
                pointer(MotionEvent.ACTION_UP, 150f, 130f)
                val cropped = document.objects.single() as InsertObject.Image
                assertEquals(350f, cropped.width); assertEquals(170f, cropped.height)
                assertEquals(1, edits)
                pointer(MotionEvent.ACTION_DOWN, 500f, 300f)
                pointer(MotionEvent.ACTION_MOVE, 450f, 270f)
                pointer(MotionEvent.ACTION_CANCEL, 450f, 270f)
                assertEquals(350f, cropped.width); assertEquals(1, edits)
                assertArrayEquals(originalBytes, asset.readBytes())
            } finally { root.deleteRecursively() }
        }
    }
}
