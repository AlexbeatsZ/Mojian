package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceModelsTest {
    @Test
    fun sampleLibraryDoesNotInventQuickNotesEntries() {
        assertEquals(listOf("我的笔记本"), NotebookLibrary.sample().notebooks.map { it.title })
    }

    @Test
    fun notebookLibrarySwitchesTheActiveWorkspace() {
        val second =
            NotebookWorkspace(
                id = "notebook-two",
                title = "第二本笔记",
                sections = mutableListOf(SectionNode("section-two", "第二分区", mutableListOf(NotePage("page-two", "第二页")))),
            )
        val library = NotebookLibrary(mutableListOf(NotebookWorkspace.sample(), second))

        assertTrue(library.selectNotebook("notebook-two"))

        assertEquals("第二本笔记", library.activeNotebook().title)
        assertEquals("第二页", library.activeNotebook().selectedPage()?.title)
    }

    @Test
    fun notebookLibraryAppliesPagePanePreferenceGlobally() {
        val library = NotebookLibrary.sample()

        library.setPagePaneMode(PagePaneMode.RECURSIVE)

        assertTrue(library.notebooks.all { it.pagePaneMode == PagePaneMode.RECURSIVE })
    }

    @Test
    fun newNotebookIsAddedAtTheTopLevelWithoutInventingASection() {
        val library = NotebookLibrary.sample()

        val added = library.addNotebook("项目笔记")

        assertEquals(listOf("我的笔记本", "项目笔记"), library.notebooks.map { it.title })
        assertEquals(added.id, library.activeNotebookId)
        assertTrue(added.sections.isEmpty())
        assertTrue(added.pagePaneRows().isEmpty())
    }

    @Test
    fun focusingNotebookMakesTheNextSectionATopLevelSection() {
        val workspace = NotebookWorkspace.sample()
        workspace.selectSection("section-notein")
        workspace.focusNotebook()

        val added = workspace.addSectionAtFocus()

        assertEquals(listOf(added.id), workspace.breadcrumb(added.id).map { it.id })
    }

    @Test
    fun sectionsCanNestWithoutAFixedDepth() {
        val workspace = NotebookWorkspace.sample()
        workspace.selectSection("section-ink")

        val levelFour = workspace.addSection("section-ink")
        val levelFive = workspace.addSection(levelFour.id)

        val row = workspace.flattenSections().first { it.section.id == levelFive.id }
        assertEquals(4, row.depth)
        assertEquals(listOf("产品设计", "Notein 兼容", "笔迹引擎", "新子分区", "新子分区"), workspace.breadcrumb().map { it.title })
    }

    @Test
    fun selectingSectionAndAddingPageUpdatesTheCurrentPage() {
        val workspace = NotebookWorkspace.sample()

        assertTrue(workspace.selectSection("section-study"))
        val page = workspace.addPage("课程整理")

        assertEquals(page.id, workspace.selectedPageId)
        assertEquals("课程整理", workspace.selectedPage()?.title)
    }

    @Test
    fun importedPdfPagesAreAddedInSourceOrderAndSelectTheFirstPage() {
        val workspace = NotebookWorkspace.sample()
        val pages =
            listOf(
                NotePage("pdf-1", "讲义 · 1", pdfPage = PdfPageInfo("pdf-1", "source.pdf", "1.png", "讲义.pdf", 1, 2, 595f, 842f)),
                NotePage("pdf-2", "讲义 · 2", pdfPage = PdfPageInfo("pdf-1", "source.pdf", "2.png", "讲义.pdf", 2, 2, 595f, 842f)),
            )

        assertTrue(workspace.addPages("section-product", pages))

        assertEquals("pdf-1", workspace.selectedPageId)
        assertEquals(listOf("page-overview", "page-flow", "pdf-1", "pdf-2"), workspace.pagePaneRows().map { (it as PagePaneRow.PageItem).page.id })
    }

    @Test
    fun sectionAddUsesFocusedSectionAndBlankFocusCreatesAtRoot() {
        val workspace = NotebookWorkspace.sample()

        workspace.selectSection("section-notein")
        val child = workspace.addSectionAtFocus()
        assertEquals("section-notein", workspace.breadcrumb(child.id).dropLast(1).last().id)

        workspace.clearSectionFocus()
        val root = workspace.addSectionAtFocus()
        assertEquals(listOf(root.id), workspace.breadcrumb(root.id).map { it.id })
    }

    @Test
    fun collapsedBranchHidesOnlyItsDescendants() {
        val workspace = NotebookWorkspace.sample()

        assertTrue(workspace.toggleExpanded("section-notein"))
        val visibleIds = workspace.flattenSections().map { it.section.id }

        assertTrue("section-notein" in visibleIds)
        assertTrue("section-ink" !in visibleIds)
        assertTrue("section-study" in visibleIds)
    }

    @Test
    fun currentSectionModeNeverMixesChildSectionsIntoThePagePane() {
        val workspace = NotebookWorkspace.sample()

        val rows = workspace.pagePaneRows()

        assertTrue(rows.all { it is PagePaneRow.PageItem })
        assertEquals(listOf("page-overview", "page-flow"), rows.map { (it as PagePaneRow.PageItem).page.id })
    }

    @Test
    fun recursiveModeShowsEveryChildSectionTogetherWithItsPages() {
        val workspace = NotebookWorkspace.sample()
        workspace.setPagePaneMode(PagePaneMode.RECURSIVE)

        val labels =
            workspace.pagePaneRows().map {
                when (it) {
                    is PagePaneRow.PageItem -> "P:${it.page.title}:${it.depth}"
                    is PagePaneRow.SectionHeader -> "S:${it.section.title}:${it.depth}"
                }
            }

        assertEquals(
            listOf(
                "P:界面与交互:0",
                "P:操作流程:0",
                "S:Notein 兼容:0",
                "P:格式研究:1",
                "S:笔迹引擎:1",
                "P:钢笔手感:2",
                "P:延迟观察:2",
                "S:灵感:0",
                "P:随手记录:1",
            ),
            labels,
        )
    }

    @Test
    fun selectingADeepPageKeepsTheBlackPaneRootStable() {
        val workspace = NotebookWorkspace.sample()
        workspace.setPagePaneMode(PagePaneMode.RECURSIVE)

        assertTrue(workspace.selectPage("section-ink", "page-latency"))

        assertEquals("section-product", workspace.selectedSectionId)
        assertEquals("section-ink", workspace.selectedPageSectionId)
        assertEquals("延迟观察", workspace.selectedPage()?.title)
        assertEquals(listOf("产品设计", "Notein 兼容", "笔迹引擎"), workspace.breadcrumb(workspace.selectedPageSectionId).map { it.title })
    }

    @Test
    fun switchingBackToCurrentSectionSelectsAVisibleDirectPage() {
        val workspace = NotebookWorkspace.sample()
        workspace.setPagePaneMode(PagePaneMode.RECURSIVE)
        workspace.selectPage("section-ink", "page-latency")

        workspace.setPagePaneMode(PagePaneMode.CURRENT_SECTION)

        assertEquals("section-product", workspace.selectedPageSectionId)
        assertEquals("page-overview", workspace.selectedPageId)
    }

    @Test
    fun focusingNotebookInCurrentSectionModeEmptiesThePagePaneAndDisablesSectionScope() {
        val workspace = NotebookWorkspace.sample()
        assertEquals(PagePaneMode.CURRENT_SECTION, workspace.pagePaneMode)
        assertTrue(workspace.hasSelectedSection())

        workspace.focusNotebook()

        org.junit.Assert.assertFalse(workspace.hasSelectedSection())
        org.junit.Assert.assertNull(workspace.focusedSectionId)
        assertTrue(workspace.pagePaneRows().isEmpty())
        org.junit.Assert.assertNull(workspace.selectedPage())
    }

    @Test
    fun focusingNotebookInRecursiveModeShowsAllSectionsSubsectionsAndPages() {
        val workspace = NotebookWorkspace.sample()
        workspace.setPagePaneMode(PagePaneMode.RECURSIVE)
        workspace.focusNotebook()

        val labels =
            workspace.pagePaneRows().map {
                when (it) {
                    is PagePaneRow.PageItem -> "P:${it.page.title}:${it.depth}"
                    is PagePaneRow.SectionHeader -> "S:${it.section.title}:${it.depth}"
                }
            }

        assertEquals(
            listOf(
                "S:产品设计:0",
                "P:界面与交互:1",
                "P:操作流程:1",
                "S:Notein 兼容:1",
                "P:格式研究:2",
                "S:笔迹引擎:2",
                "P:钢笔手感:3",
                "P:延迟观察:3",
                "S:灵感:1",
                "P:随手记录:2",
                "S:学习:0",
                "P:阅读笔记:1",
            ),
            labels,
        )
        assertEquals("page-overview", workspace.selectedPageId)
        assertEquals("section-product", workspace.selectedPageSectionId)
    }

    @Test
    fun selectingPageWhenNotebookIsFocusedInRecursiveModeUpdatesThePage() {
        val workspace = NotebookWorkspace.sample()
        workspace.setPagePaneMode(PagePaneMode.RECURSIVE)
        workspace.focusNotebook()

        assertTrue(workspace.selectPage("section-study", "page-reading"))

        org.junit.Assert.assertNull(workspace.focusedSectionId)
        assertEquals("section-study", workspace.selectedPageSectionId)
        assertEquals("page-reading", workspace.selectedPageId)
        assertEquals("阅读笔记", workspace.selectedPage()?.title)
    }

    @Test
    fun deletingASectionRemovesItsSubtreeAndSelectsANeighbor() {
        val workspace = NotebookWorkspace.sample()
        workspace.selectSection("section-product")

        val removed = workspace.deleteSection("section-product")

        assertEquals("section-product", removed?.id)
        assertNull(workspace.section("section-notein"))
        assertEquals("section-study", workspace.focusedSectionId)
        assertEquals("page-reading", workspace.selectedPageId)
    }

    @Test
    fun draggingASectionAdjacentToAnotherSectionChangesParentAndOrder() {
        val workspace = NotebookWorkspace.sample()

        assertTrue(workspace.moveSectionAdjacent("section-study", "section-notein", after = true))

        assertEquals(
            listOf("section-product", "section-notein", "section-ink", "section-study", "section-ideas"),
            workspace.flattenSections().map { it.section.id },
        )
        assertEquals(listOf("section-product", "section-study"), workspace.breadcrumb("section-study").map { it.id })
    }

    @Test
    fun pagesCanBeDeletedAndManuallySortedAcrossSections() {
        val workspace = NotebookWorkspace.sample()

        assertTrue(workspace.movePageAdjacent("section-product", "page-flow", "section-study", "page-reading", after = false))
        assertEquals(listOf("page-flow", "page-reading"), workspace.section("section-study")?.pages?.map { it.id })
        assertEquals("page-flow", workspace.deletePage("section-study", "page-flow")?.id)
        assertFalse(workspace.section("section-product")?.pages?.any { it.id == "page-flow" } == true)
    }

    @Test
    fun copyingASectionAllocatesIndependentSectionAndPageIds() {
        val workspace = NotebookWorkspace.sample()

        val copied = workspace.copySectionToParent("section-notein", null)!!

        assertTrue(copied.section.id != "section-notein")
        assertEquals(listOf("格式研究", "钢笔手感", "延迟观察"), copied.pages.map { it.copy.title })
        assertTrue(copied.pages.all { it.source.id != it.copy.id })
        copied.section.title = "副本"
        assertEquals("Notein 兼容", workspace.section("section-notein")?.title)
    }

    @Test
    fun persistedLibraryKeepsManualOrderNamesAndCollapsedDescendants() {
        val library = NotebookLibrary.sample()
        val workspace = library.activeNotebook()
        workspace.renameSection("section-ink", "笔迹")
        workspace.toggleExpanded("section-product")
        workspace.movePageAdjacent("section-product", "page-flow", "section-product", "page-overview", after = false)

        val restored = NotebookLibrary.fromJson(library.toJsonString())!!
        val restoredWorkspace = restored.activeNotebook()

        assertEquals("笔迹", restoredWorkspace.section("section-ink")?.title)
        assertFalse(restoredWorkspace.section("section-product")!!.expanded)
        assertEquals(listOf("page-flow", "page-overview"), restoredWorkspace.section("section-product")?.pages?.map { it.id })
    }
}
