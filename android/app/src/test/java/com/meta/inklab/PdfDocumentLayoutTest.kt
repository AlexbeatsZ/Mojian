package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PdfDocumentLayoutTest {
    private val sheets = listOf(ImportedPdfSheet(1, 612f, 792f, "one.png"), ImportedPdfSheet(2, 842f, 595f, "two.png"))

    @Test fun multiSheetPdfAddsOneNavigationPageAndRetainsBackgroundsOnCanonicalRoundTrip() {
        val note = PdfDocumentLayout.create("book", "pdf-note", "讲义.PDF", "original.pdf", sheets, true)
        val workspace = NotebookWorkspace.sample()
        val count = workspace.selectedSection()!!.pages.size
        assertTrue(workspace.addPages(workspace.selectedSectionId, listOf(note.page)))
        assertEquals(count + 1, workspace.selectedSection()!!.pages.size)
        assertEquals("讲义", workspace.selectedPage()!!.title)
        assertEquals(2, note.page.pdfPage!!.pageCount)
        val document = SharedWorkspaceCodec.decodeDocument(SharedWorkspaceCodec.encodeDocument(note.document))
        val backgrounds = document.objects.filterIsInstance<InsertObject.Image>()
        assertEquals(listOf(1, 2), backgrounds.map { it.sourcePageNumber })
        assertEquals(setOf("original.pdf"), backgrounds.map { it.sourceAssetName }.toSet())
        assertTrue(backgrounds.all { it.pageBackground && it.viewInverted })
        assertEquals(backgrounds[0].height, backgrounds[1].documentY, .001f)
        assertEquals(842f * DOCUMENT_UNITS_PER_PDF_POINT, note.page.viewSettings.documentWidth!!, .001f)
        assertEquals((792f + 595f) * DOCUMENT_UNITS_PER_PDF_POINT, note.page.viewSettings.documentHeight!!, .001f)
        val appearance = SharedWorkspaceCodec.decodeAppearance(SharedWorkspaceCodec.encodeAppearance(note.page.viewSettings, null))
        assertEquals(note.page.viewSettings.documentHeight!!, appearance.documentHeight!!, .001f)
    }

    @Test fun outputSplitsOneNoteBackIntoOriginalMixedSizeSheetsAndKeepsOffsetsForAnnotations() {
        val note = PdfDocumentLayout.create("book", "pdf-note", "lecture.pdf", "original.pdf", sheets, false)
        val bounds = PdfDocumentLayout.sheetBounds(note.document.objects.reversed())
        val summary = ExportSourceSummary(note.page.viewSettings, bounds, PdfOutputPageSize(612f, 792f), pdfSheets = bounds)
        val output = summary.sheets(ExportOptions()).map { it.first }
        assertEquals(2, output.size)
        assertEquals(612f, output[0].widthPoints, .001f)
        assertEquals(792f, output[0].heightPoints, .001f)
        assertEquals(842f, output[1].widthPoints, .001f)
        assertEquals(595f, output[1].heightPoints, .001f)
        assertEquals(output[0].source.bottom, output[1].source.top, 0f)
        assertTrue(note.document.objects.filterIsInstance<InsertObject.Image>().none { it.viewInverted })
        val explicit = summary.sheets(ExportOptions(paper = ExportPaper.LETTER, landscape = true)).map { it.first }
        assertEquals(2, explicit.size)
        assertEquals(bounds[1], explicit[1].source)
        explicit.forEach { assertEquals(792f, it.widthPoints, .001f); assertEquals(612f, it.heightPoints, .001f) }
        assertEquals(2, summary.sheets(ExportOptions(), PdfOutputPageSize(600f, 800f)).size)
    }

    @Test fun ordinaryPdfPrintoutsDoNotBecomePhysicalPageBoundaries() {
        val note = PdfDocumentLayout.create("book", "pdf-note", "single.pdf", "original.pdf", sheets.take(1), false)
        val copy = (note.document.objects.single() as InsertObject.Image).copy(pageBackground = false)
        assertTrue(PdfDocumentLayout.sheetBounds(listOf(copy)).isEmpty())
        assertEquals(1, ExportLayout.plan(note.page.viewSettings, emptyList(), ExportOptions(), PdfOutputPageSize(612f, 792f)).size)
    }

    @Test fun invalidOrEmptyPdfCannotCreateANote() {
        for (invalid in listOf(emptyList(), listOf(sheets[0].copy(widthPoints = Float.NaN)), listOf(sheets[0].copy(heightPoints = 0f)))) {
            try { PdfDocumentLayout.create("book", "note", "bad.pdf", "original.pdf", invalid, false); fail() }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun failedIndexPublicationDoesNotChangeSelectionOrAttachAPartialNote() {
        val library = NotebookLibrary(mutableListOf(NotebookWorkspace.sample()))
        val before = library.toJsonString()
        val notebook = library.activeNotebook()
        val note = PdfDocumentLayout.create(notebook.id, "pdf-note", "lecture.pdf", "original.pdf", sheets, false)
        try {
            PdfDocumentLayout.attach(library, notebook.id, notebook.selectedSectionId, note.page) { throw IOException("full disk") }
            fail()
        } catch (_: IOException) { }
        assertEquals(before, library.toJsonString())
        val published = PdfDocumentLayout.attach(library, notebook.id, notebook.selectedSectionId, note.page) { candidate ->
            assertEquals("pdf-note", candidate.activeNotebook().selectedPageId)
        }
        assertEquals("pdf-note", published.activeNotebook().selectedPageId)
        assertEquals(before, library.toJsonString())
    }
}
