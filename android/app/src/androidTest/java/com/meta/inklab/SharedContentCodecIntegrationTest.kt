package com.meta.inklab

import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedContentCodecIntegrationTest {
    @Test
    fun spacingOnlyChangeSurvivesRepeatedSaveAndReloadWithoutRewritingRichText() {
        val document = document()
        val original = storedText(document)
        val originalHtml = original.getString("androidRichText")
        val originalFontSize = original.getDouble("fontSize")
        val edited = SharedContentCodec.text(document).single().copy(lineSpacingMultiplier = 1.75f)

        SharedContentCodec.save(document, emptyList(), listOf(edited))
        SharedContentCodec.save(document, emptyList(), listOf(edited))

        val saved = storedText(document)
        assertEquals(1.75, saved.getDouble("lineSpacing"), 0.0)
        assertEquals(1.75, saved.getJSONObject("androidRichTextCanonical").getDouble("lineSpacing"), 0.0)
        assertEquals(originalHtml, saved.getString("androidRichText"))
        assertEquals(originalFontSize, saved.getDouble("fontSize"), 0.0)
        assertEquals("retained", saved.getJSONObject("extension").getString("unknown"))
        assertEquals("retained", saved.getJSONObject("androidRichTextCanonical").getString("unknown"))
        val reloaded = SharedContentCodec.text(document).single()
        assertEquals(1.75f, reloaded.lineSpacingMultiplier, 0f)
        assertEquals(1.25f, reloaded.contentScale, 0f)
        val richText = reloaded.text as Spanned
        assertTrue(richText.getSpans(0, richText.length, StyleSpan::class.java).isNotEmpty())
    }

    @Test
    fun unchangedRestoreDoesNotRewriteCanonicalText() {
        val document = document()
        val restored = SharedContentCodec.text(document)
        val original = storedText(document).toString()

        SharedContentCodec.save(document, emptyList(), restored)

        assertEquals(original, storedText(document).toString())
    }

    @Test
    fun legacyRichTextWithoutCanonicalBasisRetainsCompatibilityAfterSpacingEdit() {
        val document = document()
        storedText(document).remove("androidRichTextCanonical")
        val edited = SharedContentCodec.text(document).single().copy(lineSpacingMultiplier = 2f)

        SharedContentCodec.save(document, emptyList(), listOf(edited))

        assertTrue(!storedText(document).has("androidRichTextCanonical"))
        val reloaded = SharedContentCodec.text(document).single()
        assertEquals(2f, reloaded.lineSpacingMultiplier, 0f)
        assertEquals(1.25f, reloaded.contentScale, 0f)
    }

    @Test
    fun readOnlyExportDecodeDoesNotReplaceEditorBaseline() {
        val document = document()
        val restored = SharedContentCodec.text(document)
        val original = storedText(document).toString()
        val exportCopy = PageInsertDocument(document.notebookId, document.pageId,
            sharedSource = JSONObject(document.sharedSource.toString()))
        storedText(exportCopy).put("text", "External update").put("fontSize", 42)

        assertEquals("External update", SharedContentCodec.readText(exportCopy).single().text.toString())
        SharedContentCodec.save(document, emptyList(), restored)

        assertEquals(original, storedText(document).toString())
    }

    private fun document(): PageInsertDocument {
        val text = JSONObject().put("id", "text").put("text", "Hello")
            .put("x", 24.0).put("y", 48.0).put("width", 300.0).put("height", 50.0)
            .put("fontSize", 19.5).put("bold", true).put("italic", false).put("underline", false)
            .put("foregroundHex", "#123456").put("lineSpacing", 1.0)
            .put("androidRichText", "<p><b>Hello</b></p>").put("androidContentScale", 1.25)
            .put("extension", JSONObject().put("unknown", "retained"))
        text.put("androidRichTextCanonical", JSONObject().apply {
            listOf("fontSize", "bold", "italic", "underline", "foregroundHex", "lineSpacing").forEach { key ->
                put(key, text.get(key))
            }
            put("unknown", "retained")
        })
        return PageInsertDocument("codec-test", java.util.UUID.randomUUID().toString(),
            sharedSource = JSONObject().put("textItems", JSONArray().put(text)))
    }

    private fun storedText(document: PageInsertDocument): JSONObject =
        document.sharedSource.getJSONArray("textItems").getJSONObject(0)
}
