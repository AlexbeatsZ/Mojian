package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceModelsTest {
    @Test
    fun notebookLibrarySwitchesTheActiveWorkspace() {
        val library = NotebookLibrary.sample()

        assertTrue(library.selectNotebook("notebook-quick"))

        assertEquals("随手笔记", library.activeNotebook().title)
        assertEquals("快速记录", library.activeNotebook().selectedPage()?.title)
    }

    @Test
    fun notebookLibraryAppliesPagePanePreferenceGlobally() {
        val library = NotebookLibrary.sample()

        library.setPagePaneMode(PagePaneMode.RECURSIVE)

        assertTrue(library.notebooks.all { it.pagePaneMode == PagePaneMode.RECURSIVE })
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
}
