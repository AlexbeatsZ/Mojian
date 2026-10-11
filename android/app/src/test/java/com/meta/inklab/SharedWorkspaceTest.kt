package com.meta.inklab

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedWorkspaceTest {
    private fun normalized(value: Any?): Any? = when(value) {
        is JSONObject -> value.keys().asSequence().sorted().associateWith { normalized(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        is Number -> value.toDouble()
        else -> value
    }
    private fun page() = JSONObject("""{"version":2,"notebookId":"book","pageId":"page","revision":3,
        "sourceExtension":{"retain":true},"layers":[{"id":"ink","order":2,"visible":false}],
        "textItems":[{"id":"text","text":"original","sourceFont":"retain"}],
        "tags":[{"id":"tag","kind":0,"documentX":7,"documentY":9,"scale":2}],
        "strokes":[{"id":"stroke","points":[{"x":1,"y":2,"pressure":0.3,"elapsedMilliseconds":4}],"vectorPath":"M1,2L3,4Z","sourceBrush":{"retain":true}}],
        "objects":[{"id":"image","kind":3,"x":11,"y":12,"width":13,"height":14,"assetName":"page.png","sourceAssetName":"original.pdf","sourcePageNumber":2,
        "isPageBackground":true,"layerId":"ink","zOrder":8,"foreignMetadata":{"retain":true}}]}""")

    private fun library() = JSONObject("""{"version":2,"activeNotebookId":"book","sourceLibrary":"retain","notebooks":[
        {"id":"book","title":"笔记","expanded":true,"focusedSectionId":"section","selectedPageSectionId":"section","selectedPageId":"page","pagePaneMode":1,
        "sections":[{"id":"section","title":"分区","expanded":true,"children":[],"pages":[{"id":"page","title":"页面","createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-01T00:00:00Z",
        "sourcePage":"retain","appearance":{"paperKind":1,"theme":1,"background":10,"pageColor":"#AABBCC","showTitle":false,"sheetCount":2,"width":793.7,"height":1122.5,"infiniteWidth":1000,"infiniteHeight":1400,"sourceAppearance":"retain"}}]}]}]}""")

    @Test fun canonicalLibraryRoundTripPreservesSourceAndLocalSelectionDoesNotRewriteIt() {
        val source=library()
        val decoded=SharedWorkspaceCodec.decodeLibrary(source)
        assertEquals(CanvasBackgroundFormat.RULE_TWO_COLUMN_STANDARD,decoded.activeNotebook().selectedPage()!!.viewSettings.backgroundFormat)
        decoded.activeNotebook().clearSectionFocus()
        val result=SharedWorkspaceCodec.encodeLibrary(decoded,source)
        assertEquals(normalized(source),normalized(result))
        decoded.activeNotebook().title="renamed"
        val edited=SharedWorkspaceCodec.encodeLibrary(decoded,source)
        assertEquals("renamed",edited.getJSONArray("notebooks").getJSONObject(0).getString("title"))
        assertEquals("retain",edited.getString("sourceLibrary"))
    }

    @Test fun allPaperAndBackgroundValuesUseStableDesktopOrdinals() {
        for(paper in 0..2) for(background in 0..13) {
            val source=library().getJSONArray("notebooks").getJSONObject(0).getJSONArray("sections").getJSONObject(0).getJSONArray("pages").getJSONObject(0).getJSONObject("appearance")
                .put("paperKind",paper).put("background",background)
            val result=SharedWorkspaceCodec.encodeAppearance(SharedWorkspaceCodec.decodeAppearance(source),source)
            assertEquals(paper,result.getInt("paperKind"));assertEquals(background,result.getInt("background"))
            assertEquals("#AABBCC",result.getString("pageColor"))
        }
    }

    @Test fun pageCodecPreservesExactVectorsLayersMixedContentAndUnknownFields() {
        val source=page()
        val document=SharedWorkspaceCodec.decodeDocument(source)
        assertEquals(normalized(source),normalized(SharedWorkspaceCodec.encodeDocument(document)))
        val image=document.objects.single() as InsertObject.Image
        assertEquals(ImageSourceKind.PDF_PAGE,image.sourceKind)
        assertTrue(image.pageBackground)
        image.documentX+=100f
        val result=SharedWorkspaceCodec.encodeDocument(document)
        assertEquals(11+100*SharedWorkspaceCodec.TO_SHARED,result.getJSONArray("objects").getJSONObject(0).getDouble("x"),.001)
        for(key in listOf("strokes","layers","tags","textItems")) assertEquals(normalized(source.getJSONArray(key)),normalized(result.getJSONArray(key)))
        assertTrue(result.getJSONObject("sourceExtension").getBoolean("retain"))
    }

    @Test fun canonicalImagesAcceptExplicitNullPdfMetadataAndPreserveIt() {
        val source=page()
        val imageJson=source.getJSONArray("objects").getJSONObject(0)
            .put("sourceAssetName",JSONObject.NULL).put("sourcePageNumber",JSONObject.NULL)
        val document=SharedWorkspaceCodec.decodeDocument(source)
        val image=document.objects.single() as InsertObject.Image
        assertNull(image.sourceAssetName)
        assertNull(image.sourcePageNumber)
        assertEquals(normalized(source),normalized(SharedWorkspaceCodec.encodeDocument(document)))
        assertTrue(imageJson.isNull("sourcePageNumber"))
    }

    @Test fun streamingJsonPreservesCanonicalTypesEscapesAndExactNoOpFingerprint() {
        val source=page().put("escaped","first\r\nsecond \"quoted\" 中文")
            .put("optional",JSONObject.NULL).put("fraction",1.25).put("enabled",true)
        val writer=java.io.StringWriter()
        WorkspaceJson.write(source,writer)
        assertEquals(normalized(source),normalized(JSONObject(writer.toString())))
        assertEquals(WorkspaceJson.fingerprint(source),WorkspaceJson.fingerprint(JSONObject(writer.toString())))
        assertNotEquals(WorkspaceJson.fingerprint(source),WorkspaceJson.fingerprint(JSONObject(writer.toString()).put("fraction",1.5)))
    }

    @Test fun repositoryRejectsCorruptionMissingReceivedPagesAndStaleWrites() {
        val root=Files.createTempDirectory(File(System.getProperty("java.io.tmpdir"),".agents").apply{mkdirs()}.toPath(),"mojian-shared-test").toFile()
        try {
            File(root,"library.json").writeText(library().toString())
            val repository=PageContentRepository(root)
            val loadedLibrary=repository.loadLibrary()
            assertThrows(IllegalStateException::class.java) { repository.load("book","page") }
            val file=repository.pageFile("book","page").apply { parentFile!!.mkdirs();writeText(page().toString()) }
            val document=repository.load("book","page")
            assertSame(document,repository.load("book","page")) // Native display/export share one parsed received page.
            repository.save(document)
            assertEquals(3,document.sharedSource.getLong("revision"))
            assertFalse(File(file.parentFile,"page.json.previous").exists())
            document.sharedSource.put("edited",true)
            // An actual edit replaces the source; change object geometry to exercise the canonical write.
            document.objects.single().documentX+=2f
            repository.save(document)
            assertTrue(File(file.parentFile,"page.json.previous").isFile)
            file.writeText("corrupt")
            assertThrows(Exception::class.java) { repository.load("book","page") }
            assertThrows(IllegalStateException::class.java) { repository.save(document) }
            assertEquals("corrupt",file.readText())
            file.writeText(page().put("remoteChange",true).toString())
            repository.load("book","page") // An export/read must not authorize an older document to overwrite this version.
            assertThrows(IllegalStateException::class.java) { repository.save(document) }
            loadedLibrary.activeNotebook().title="local rename"
            File(root,"library.json").writeText(library().put("remoteChange",true).toString())
            assertThrows(IllegalStateException::class.java) { repository.saveLibrary(loadedLibrary) }
            assertTrue(JSONObject(File(root,"library.json").readText()).getBoolean("remoteChange"))
        } finally { root.deleteRecursively() }
    }

    @Test fun absentDevicePreferencesAndLargeExtentsDoNotCreateLibraryWritesWhenRead() {
        val source=library()
        val notebook=source.getJSONArray("notebooks").getJSONObject(0)
        notebook.remove("pagePaneMode")
        notebook.getJSONArray("sections").getJSONObject(0).getJSONArray("pages").getJSONObject(0).getJSONObject("appearance")
            .put("infiniteHeight",29846.92913385827)
        assertEquals(normalized(source),normalized(SharedWorkspaceCodec.encodeLibrary(SharedWorkspaceCodec.decodeLibrary(source),source)))
    }

    @Test fun serialPageWritesRetainLatestContentAndRevisionAndRefuseExternalOverwrite() {
        val root=Files.createTempDirectory(File(System.getProperty("java.io.tmpdir"),".agents").apply{mkdirs()}.toPath(),"mojian-queue-test").toFile()
        val queue=PageWriteQueue()
        try {
            val repository=PageContentRepository(root)
            val document=repository.load("book","new-page")
            for(index in 1..3) {
                val source=JSONObject().put("textItems",JSONArray().put(JSONObject().put("text","revision-$index")))
                queue.submit(repository,document,PageInsertDocument("book","new-page",sharedSource=source)) {}
            }
            queue.flush()
            val current=repository.load("book","new-page")
            assertEquals(3,current.sharedSource.getLong("revision"))
            assertEquals("revision-3",current.sharedSource.getJSONArray("textItems").getJSONObject(0).getString("text"))
            repository.pageFile("book","new-page").writeText(SharedWorkspaceCodec.encodeDocument(current).put("remoteChange",true).toString())
            queue.submit(repository,document,PageInsertDocument("book","new-page")) {}
            assertThrows(IllegalStateException::class.java) { queue.flush() }
            assertTrue(queue.hasFailures)
            assertTrue(JSONObject(repository.pageFile("book","new-page").readText()).getBoolean("remoteChange"))
        } finally { queue.close();root.deleteRecursively() }
    }
}
