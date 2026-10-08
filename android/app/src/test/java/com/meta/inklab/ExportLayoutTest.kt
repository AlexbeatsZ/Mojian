package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test

class ExportLayoutTest {
    @Test fun occupiedA4SheetsIgnoreViewportGrowthAndNeverDropGapInk() {
        val settings=PageViewSettings(paperSize=PaperSize.A4,a4PageCount=9)
        val content=listOf(StrokeViewportBounds(-10f,A4PageLayout.pageHeight-10,200f,A4PageLayout.pageTop(1)+10))
        val sheets=ExportLayout.plan(settings,content,ExportOptions())
        assertEquals(2,sheets.size)
        assertEquals(A4PageLayout.pageTop(1),sheets[0].source.bottom,0.01f)
        assertEquals(sheets[0].source.bottom,sheets[1].source.top,0.01f)
        assertEquals(-10f,sheets[0].source.left,0f)
        assertEquals(9,ExportLayout.plan(settings,content,ExportOptions(trimTrailingBlank=false)).size)
        assertEquals(9,settings.a4PageCount)
    }
    @Test fun boundlessTallWideContentIsSlicedWithoutDistortingOrLosingOverflow() {
        val content=listOf(StrokeViewportBounds(-500f,-1000f,20000f,60000f))
        val sheets=ExportLayout.plan(PageViewSettings(),content,ExportOptions())
        assertTrue(sheets.size>1)
        for(i in 1 until sheets.size) assertEquals(sheets[i-1].source.bottom,sheets[i].source.top,0.01f)
        assertEquals(-1000f,sheets.first().source.top,0f)
        assertEquals(60000f,sheets.last().source.bottom,0f)
        sheets.forEach { assertEquals(595.2756f,it.widthPoints,0.01f); assertEquals(841.8898f,it.heightPoints,0.01f) }
        assertEquals(1,ExportLayout.plan(PageViewSettings(),emptyList(),ExportOptions()).size)
    }
    @Test fun originalPdfMediaBoxIsUsedOnlyForAutomaticPaper() {
        val settings=PageViewSettings(paperSize=PaperSize.PDF,customDocumentWidth=4000f,customDocumentHeight=6000f)
        val original=ExportLayout.plan(settings,emptyList(),ExportOptions(),PdfOutputPageSize(612.3f,792.7f)).single()
        assertEquals(612.3f,original.widthPoints,0f); assertEquals(792.7f,original.heightPoints,0f)
        assertEquals(0f,original.marginPoints,0f)
        val explicit=ExportLayout.plan(settings,emptyList(),ExportOptions(paper=ExportPaper.LETTER,landscape=true)).first()
        assertEquals(792f,explicit.widthPoints,0.01f); assertEquals(612f,explicit.heightPoints,0.01f)
    }
    @Test fun physicalPageRangesAreSortedDeduplicatedAndRejectInvalidInput() {
        assertEquals(listOf(0,1,2,4),ExportLayout.selectPages("5,1-3,2",6))
        for(range in listOf("0","1-4","2-1","1,","a")) {
            try { ExportLayout.selectPages(range,3); fail(range) } catch(_:IllegalArgumentException) {}
        }
    }
}
