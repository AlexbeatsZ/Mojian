package com.meta.inklab

data class NotePage(
    val id: String,
    var title: String,
    val subtitle: String = "刚刚",
)

data class SectionNode(
    val id: String,
    var title: String,
    val pages: MutableList<NotePage> = mutableListOf(),
    val children: MutableList<SectionNode> = mutableListOf(),
    var expanded: Boolean = true,
)

data class SectionRow(val section: SectionNode, val depth: Int)

enum class PagePaneMode {
    CURRENT_SECTION,
    RECURSIVE,
}

sealed interface PagePaneRow {
    val depth: Int

    data class SectionHeader(
        val section: SectionNode,
        override val depth: Int,
    ) : PagePaneRow

    data class PageItem(
        val page: NotePage,
        val sectionId: String,
        override val depth: Int,
    ) : PagePaneRow
}

class NotebookWorkspace(
    val id: String,
    var title: String,
    val sections: MutableList<SectionNode>,
    var expanded: Boolean = true,
) {
    private var sequence = 100

    var focusedSectionId: String? = flattenSections().firstOrNull()?.section?.id
        private set

    val selectedSectionId: String
        get() = focusedSectionId.orEmpty()

    var pagePaneMode: PagePaneMode = PagePaneMode.CURRENT_SECTION
        private set

    var selectedPageSectionId: String = focusedSectionId.orEmpty()
        private set

    var selectedPageId: String = focusedSectionId?.let { findSection(it) }?.pages?.firstOrNull()?.id.orEmpty()
        private set

    fun flattenSections(): List<SectionRow> {
        val rows = mutableListOf<SectionRow>()
        fun visit(nodes: List<SectionNode>, depth: Int) {
            for (node in nodes) {
                rows += SectionRow(node, depth)
                if (node.expanded) visit(node.children, depth + 1)
            }
        }
        visit(sections, 0)
        return rows
    }

    fun selectedSection(): SectionNode? =
        focusedSectionId?.let(::findSection)

    fun hasSelectedSection(): Boolean =
        focusedSectionId != null && findSection(focusedSectionId!!) != null

    fun selectedPage(): NotePage? =
        findSection(selectedPageSectionId)?.pages?.firstOrNull { it.id == selectedPageId }

    fun pagePaneRows(): List<PagePaneRow> {
        val focusedId = focusedSectionId
        if (focusedId == null) {
            if (pagePaneMode == PagePaneMode.CURRENT_SECTION) {
                return emptyList()
            }
            val rows = mutableListOf<PagePaneRow>()
            fun visit(section: SectionNode, depth: Int) {
                rows += PagePaneRow.SectionHeader(section, depth)
                rows += section.pages.map { PagePaneRow.PageItem(it, section.id, depth + 1) }
                for (child in section.children) {
                    visit(child, depth + 1)
                }
            }
            for (section in sections) {
                visit(section, 0)
            }
            return rows
        }

        val root = findSection(focusedId) ?: return emptyList()
        if (pagePaneMode == PagePaneMode.CURRENT_SECTION) {
            return root.pages.map { PagePaneRow.PageItem(it, root.id, 0) }
        }

        val rows = mutableListOf<PagePaneRow>()
        rows += root.pages.map { PagePaneRow.PageItem(it, root.id, 0) }
        fun visit(section: SectionNode, depth: Int) {
            rows += PagePaneRow.SectionHeader(section, depth)
            rows += section.pages.map { PagePaneRow.PageItem(it, section.id, depth + 1) }
            for (child in section.children) visit(child, depth + 1)
        }
        for (child in root.children) visit(child, 0)
        return rows
    }

    fun selectSection(id: String): Boolean {
        val section = findSection(id) ?: return false
        focusedSectionId = section.id
        selectFirstPageForMode()
        return true
    }

    fun clearSectionFocus() {
        focusedSectionId = null
        selectFirstPageForMode()
    }

    fun focusNotebook() {
        focusedSectionId = null
        selectFirstPageForMode()
    }

    fun selectPage(id: String): Boolean {
        return selectPage(selectedPageSectionId.ifEmpty { focusedSectionId.orEmpty() }, id)
    }

    fun selectPage(sectionId: String, id: String): Boolean {
        val section = findSection(sectionId) ?: return false
        val page = section.pages.firstOrNull { it.id == id } ?: return false
        val focusedId = focusedSectionId
        if (focusedId == null) {
            if (pagePaneMode == PagePaneMode.CURRENT_SECTION) return false
        } else {
            if (pagePaneMode == PagePaneMode.CURRENT_SECTION && section.id != focusedId) return false
            val selectedRoot = findSection(focusedId) ?: return false
            if (pagePaneMode == PagePaneMode.RECURSIVE && !isDescendantOrSame(selectedRoot, section.id)) return false
        }
        selectedPageSectionId = section.id
        selectedPageId = page.id
        return true
    }

    fun setPagePaneMode(mode: PagePaneMode) {
        if (pagePaneMode == mode) return
        pagePaneMode = mode
        if (mode == PagePaneMode.CURRENT_SECTION || selectedPage() == null) {
            selectFirstPageForMode()
        }
    }

    private fun selectFirstPageForMode() {
        val focusedId = focusedSectionId
        if (focusedId == null) {
            if (pagePaneMode == PagePaneMode.CURRENT_SECTION) {
                selectedPageSectionId = ""
                selectedPageId = ""
            } else {
                val first = firstPageInNotebook()
                selectedPageSectionId = first?.first.orEmpty()
                selectedPageId = first?.second?.id.orEmpty()
            }
            return
        }
        val root = findSection(focusedId)
        if (root == null) {
            selectedPageSectionId = ""
            selectedPageId = ""
            return
        }
        val first =
            if (pagePaneMode == PagePaneMode.CURRENT_SECTION) {
                root.pages.firstOrNull()?.let { focusedId to it }
            } else {
                firstPageInTree(root)
            }
        selectedPageSectionId = first?.first ?: focusedId
        selectedPageId = first?.second?.id.orEmpty()
    }

    private fun firstPageInNotebook(): Pair<String, NotePage>? {
        for (section in sections) {
            firstPageInTree(section)?.let { return it }
        }
        return null
    }

    private fun firstPageInTree(section: SectionNode): Pair<String, NotePage>? {
        section.pages.firstOrNull()?.let { return section.id to it }
        for (child in section.children) firstPageInTree(child)?.let { return it }
        return null
    }

    private fun isDescendantOrSame(root: SectionNode, candidateId: String): Boolean {
        if (root.id == candidateId) return true
        return root.children.any { isDescendantOrSame(it, candidateId) }
    }
    fun toggleExpanded(id: String): Boolean {
        val section = findSection(id) ?: return false
        if (section.children.isEmpty()) return false
        section.expanded = !section.expanded
        return true
    }

    fun addSection(parentId: String? = null): SectionNode {
        val node = SectionNode(nextId("section"), if (parentId == null) "新分区" else "新子分区")
        if (parentId == null) {
            sections += node
        } else {
            val parent = findSection(parentId) ?: error("Parent section not found: $parentId")
            parent.children += node
            parent.expanded = true
        }
        selectSection(node.id)
        return node
    }

    fun addSectionAtFocus(): SectionNode = addSection(focusedSectionId)

    fun addPage(title: String = "无标题页"): NotePage {
        val section = selectedSection() ?: error("Cannot add page without a selected section")
        val page = NotePage(nextId("page"), title)
        section.pages += page
        selectedPageSectionId = section.id
        selectedPageId = page.id
        return page
    }

    fun breadcrumb(sectionId: String? = focusedSectionId): List<SectionNode> {
        if (sectionId == null) return emptyList()
        fun descend(nodes: List<SectionNode>, path: MutableList<SectionNode>): List<SectionNode>? {
            for (node in nodes) {
                path += node
                if (node.id == sectionId) return path.toList()
                descend(node.children, path)?.let { return it }
                path.removeAt(path.lastIndex)
            }
            return null
        }
        return descend(sections, mutableListOf()).orEmpty()
    }

    private fun findSection(id: String): SectionNode? {
        fun search(nodes: List<SectionNode>): SectionNode? {
            for (node in nodes) {
                if (node.id == id) return node
                search(node.children)?.let { return it }
            }
            return null
        }
        return search(sections)
    }

    private fun nextId(prefix: String): String = "$prefix-${sequence++}"

    companion object {
        fun sample(): NotebookWorkspace =
            NotebookWorkspace(
                id = "notebook-personal",
                title = "我的笔记本",
                sections =
                    mutableListOf(
                        SectionNode(
                            id = "section-product",
                            title = "产品设计",
                            pages = mutableListOf(NotePage("page-overview", "界面与交互"), NotePage("page-flow", "操作流程")),
                            children =
                                mutableListOf(
                                    SectionNode(
                                        id = "section-notein",
                                        title = "Notein 兼容",
                                        pages = mutableListOf(NotePage("page-format", "格式研究")),
                                        children =
                                            mutableListOf(
                                                SectionNode(
                                                    id = "section-ink",
                                                    title = "笔迹引擎",
                                                    pages = mutableListOf(NotePage("page-brush", "钢笔手感"), NotePage("page-latency", "延迟观察")),
                                                ),
                                            ),
                                    ),
                                    SectionNode("section-ideas", "灵感", mutableListOf(NotePage("page-ideas", "随手记录"))),
                                ),
                        ),
                        SectionNode("section-study", "学习", mutableListOf(NotePage("page-reading", "阅读笔记"))),
                    ),
            )
    }
}

class NotebookLibrary(
    val notebooks: MutableList<NotebookWorkspace>,
) {
    private var sequence = notebooks.size + 1

    var activeNotebookId: String = notebooks.firstOrNull()?.id ?: error("Notebook library cannot be empty")
        private set

    fun activeNotebook(): NotebookWorkspace =
        notebooks.firstOrNull { it.id == activeNotebookId } ?: error("Active notebook is missing")

    fun selectNotebook(id: String): Boolean {
        if (notebooks.none { it.id == id }) return false
        activeNotebookId = id
        return true
    }

    fun addNotebook(title: String = "新笔记本"): NotebookWorkspace {
        val inheritedMode = activeNotebook().pagePaneMode
        val notebook =
            NotebookWorkspace(
                id = "notebook-${sequence++}",
                title = title,
                sections = mutableListOf(),
            ).also { it.setPagePaneMode(inheritedMode) }
        notebooks += notebook
        activeNotebookId = notebook.id
        notebook.focusNotebook()
        return notebook
    }

    fun setPagePaneMode(mode: PagePaneMode) {
        notebooks.forEach { it.setPagePaneMode(mode) }
    }

    companion object {
        fun sample(): NotebookLibrary =
            NotebookLibrary(
                mutableListOf(
                    NotebookWorkspace.sample(),
                ),
            )
    }
}
