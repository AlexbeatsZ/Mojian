package com.meta.inklab

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PdfPageInfo(
    val sourceOwnerPageId: String,
    val sourceAssetName: String,
    val renderedAssetName: String,
    val sourceDocumentName: String,
    val pageNumber: Int,
    val pageCount: Int,
    val widthPoints: Float,
    val heightPoints: Float,
)

data class NotePage(
    val id: String,
    var title: String,
    val subtitle: String = "刚刚",
    val viewSettings: PageViewSettings = PageViewSettings(),
    var pdfPage: PdfPageInfo? = null,
)

data class WorkspaceLocation(
    val notebookId: String,
    val focusedSectionId: String?,
    val selectedPageSectionId: String,
    val pageId: String,
)

data class SectionNode(
    val id: String,
    var title: String,
    val pages: MutableList<NotePage> = mutableListOf(),
    val children: MutableList<SectionNode> = mutableListOf(),
    var expanded: Boolean = true,
)

data class SectionRow(val section: SectionNode, val depth: Int)

data class PageCopyMapping(
    val source: NotePage,
    val copy: NotePage,
)

data class SectionCopyResult(
    val section: SectionNode,
    val pages: List<PageCopyMapping>,
)

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

    fun allSections(): List<SectionNode> {
        val result = mutableListOf<SectionNode>()
        fun visit(nodes: List<SectionNode>) {
            for (node in nodes) {
                result += node
                visit(node.children)
            }
        }
        visit(sections)
        return result
    }

    fun section(id: String): SectionNode? = findSection(id)

    fun page(sectionId: String, pageId: String): NotePage? =
        findSection(sectionId)?.pages?.firstOrNull { it.id == pageId }

    fun pagesInSectionTree(sectionId: String): List<NotePage> {
        val root = findSection(sectionId) ?: return emptyList()
        val result = mutableListOf<NotePage>()
        fun visit(section: SectionNode) {
            result += section.pages
            section.children.forEach(::visit)
        }
        visit(root)
        return result
    }

    fun selectedSection(): SectionNode? =
        focusedSectionId?.let(::findSection)

    fun hasSelectedSection(): Boolean =
        focusedSectionId != null && findSection(focusedSectionId!!) != null

    fun selectedPage(): NotePage? =
        findSection(selectedPageSectionId)?.pages?.firstOrNull { it.id == selectedPageId }

    fun captureLocation(): WorkspaceLocation =
        WorkspaceLocation(id, focusedSectionId, selectedPageSectionId, selectedPageId)

    fun restoreLocation(location: WorkspaceLocation): Boolean {
        if (location.notebookId != id) return false
        if (location.pageId.isEmpty()) {
            focusedSectionId = location.focusedSectionId?.takeIf { findSection(it) != null }
            selectedPageSectionId = location.selectedPageSectionId.takeIf { findSection(it) != null }.orEmpty()
            selectedPageId = ""
            return true
        }
        val owner = findSection(location.selectedPageSectionId) ?: return false
        if (owner.pages.none { it.id == location.pageId }) return false
        focusedSectionId = location.focusedSectionId?.takeIf { findSection(it) != null }
        selectedPageSectionId = owner.id
        selectedPageId = location.pageId
        return true
    }

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

    fun renameSection(id: String, title: String): Boolean {
        val section = findSection(id) ?: return false
        val normalized = title.trim()
        if (normalized.isEmpty()) return false
        section.title = normalized
        return true
    }

    fun deleteSection(id: String): SectionNode? {
        val position = locateSection(id) ?: return null
        val removed = position.siblings.removeAt(position.index)
        val removedIds = removed.sectionIds()
        val focusWasRemoved = focusedSectionId in removedIds
        val selectedPageWasRemoved = selectedPageSectionId in removedIds

        if (focusWasRemoved) {
            focusedSectionId =
                position.siblings.getOrNull(position.index)?.id
                    ?: position.siblings.getOrNull(position.index - 1)?.id
                    ?: position.parentId
                    ?: sections.firstOrNull()?.id
        }
        if (focusWasRemoved || selectedPageWasRemoved || selectedPage() == null) selectFirstPageForMode()
        return removed
    }

    fun moveSectionAdjacent(sectionId: String, targetSectionId: String, after: Boolean): Boolean {
        if (sectionId == targetSectionId) return false
        val moving = findSection(sectionId) ?: return false
        if (targetSectionId in moving.sectionIds()) return false
        val sourcePosition = locateSection(sectionId) ?: return false
        sourcePosition.siblings.removeAt(sourcePosition.index)
        val targetPosition = locateSection(targetSectionId)
        if (targetPosition == null) {
            sourcePosition.siblings.add(sourcePosition.index.coerceAtMost(sourcePosition.siblings.size), moving)
            return false
        }
        val insertion = targetPosition.index + if (after) 1 else 0
        targetPosition.siblings.add(insertion.coerceIn(0, targetPosition.siblings.size), moving)
        return true
    }

    fun moveSectionToParent(sectionId: String, parentId: String?, index: Int = Int.MAX_VALUE): Boolean {
        val moving = findSection(sectionId) ?: return false
        if (parentId != null && parentId in moving.sectionIds()) return false
        val sourcePosition = locateSection(sectionId) ?: return false
        val destination = parentId?.let(::findSection)?.children ?: sections.takeIf { parentId == null } ?: return false
        sourcePosition.siblings.removeAt(sourcePosition.index)
        val adjustedIndex =
            if (sourcePosition.siblings === destination && sourcePosition.index < index) index - 1 else index
        destination.add(adjustedIndex.coerceIn(0, destination.size), moving)
        parentId?.let(::findSection)?.expanded = true
        return true
    }

    fun mergeSectionInto(sectionId: String, targetSectionId: String): Boolean {
        if (sectionId == targetSectionId) return false
        val source = findSection(sectionId) ?: return false
        val target = findSection(targetSectionId) ?: return false
        if (targetSectionId in source.sectionIds()) return false
        val position = locateSection(sectionId) ?: return false
        position.siblings.removeAt(position.index)
        target.pages += source.pages
        target.children += source.children
        target.expanded = true
        if (focusedSectionId == source.id) focusedSectionId = target.id
        if (selectedPageSectionId == source.id) selectedPageSectionId = target.id
        if (selectedPage() == null) selectFirstPageForMode()
        return true
    }

    fun copySectionToParent(sectionId: String, parentId: String?, index: Int = Int.MAX_VALUE): SectionCopyResult? {
        val source = findSection(sectionId) ?: return null
        if (parentId != null && parentId in source.sectionIds()) return null
        val destination = parentId?.let(::findSection)?.children ?: sections.takeIf { parentId == null } ?: return null
        val pageIds = source.allPages().associate { it.id to nextId("page") }
        val mappings = mutableListOf<PageCopyMapping>()
        fun cloneSection(section: SectionNode): SectionNode =
            SectionNode(
                id = nextId("section"),
                title = section.title,
                pages =
                    section.pages.mapTo(mutableListOf()) { page ->
                        val copiedId = pageIds.getValue(page.id)
                        val pdfPage = page.pdfPage
                        val copied =
                            page.copy(
                                id = copiedId,
                                viewSettings = page.viewSettings.copy(),
                                pdfPage =
                                    pdfPage?.copy(
                                        sourceOwnerPageId = pageIds[pdfPage.sourceOwnerPageId] ?: pdfPage.sourceOwnerPageId,
                                    ),
                            )
                        mappings += PageCopyMapping(page, copied)
                        copied
                    },
                children = section.children.mapTo(mutableListOf(), ::cloneSection),
                expanded = section.expanded,
            )
        val copied = cloneSection(source)
        destination.add(index.coerceIn(0, destination.size), copied)
        parentId?.let(::findSection)?.expanded = true
        focusedSectionId = copied.id
        selectFirstPageForMode()
        return SectionCopyResult(copied, mappings)
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

    fun addPage(
        title: String = "无标题页",
        viewSettings: PageViewSettings = PageViewSettings(),
    ): NotePage {
        val section = selectedSection() ?: error("Cannot add page without a selected section")
        val page = NotePage(nextId("page"), title, viewSettings = viewSettings)
        section.pages += page
        selectedPageSectionId = section.id
        selectedPageId = page.id
        return page
    }

    fun addPages(
        sectionId: String,
        pages: List<NotePage>,
        selectedPageId: String = pages.firstOrNull()?.id.orEmpty(),
    ): Boolean {
        if (pages.isEmpty()) return false
        val section = findSection(sectionId) ?: return false
        section.pages += pages
        focusedSectionId = section.id
        selectedPageSectionId = section.id
        this.selectedPageId = selectedPageId.takeIf { id -> pages.any { it.id == id } } ?: pages.first().id
        return true
    }

    fun renamePage(sectionId: String, pageId: String, title: String): Boolean {
        val page = page(sectionId, pageId) ?: return false
        val normalized = title.trim()
        if (normalized.isEmpty()) return false
        page.title = normalized
        return true
    }

    fun deletePage(sectionId: String, pageId: String): NotePage? {
        val section = findSection(sectionId) ?: return null
        val index = section.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return null
        val removed = section.pages.removeAt(index)
        if (selectedPageSectionId == sectionId && selectedPageId == pageId) {
            val replacement = section.pages.getOrNull(index) ?: section.pages.getOrNull(index - 1)
            if (replacement != null) {
                selectedPageSectionId = sectionId
                selectedPageId = replacement.id
            } else {
                selectFirstPageForMode()
            }
        }
        return removed
    }

    fun movePage(
        sourceSectionId: String,
        pageId: String,
        targetSectionId: String,
        index: Int = Int.MAX_VALUE,
    ): Boolean {
        val source = findSection(sourceSectionId) ?: return false
        val target = findSection(targetSectionId) ?: return false
        val sourceIndex = source.pages.indexOfFirst { it.id == pageId }
        if (sourceIndex < 0) return false
        val page = source.pages.removeAt(sourceIndex)
        val adjustedIndex = if (source === target && sourceIndex < index) index - 1 else index
        target.pages.add(adjustedIndex.coerceIn(0, target.pages.size), page)
        if (selectedPageId == pageId && selectedPageSectionId == sourceSectionId) {
            selectedPageSectionId = targetSectionId
            if (pagePaneMode == PagePaneMode.CURRENT_SECTION) focusedSectionId = targetSectionId
        }
        return true
    }

    fun movePageAdjacent(
        sourceSectionId: String,
        pageId: String,
        targetSectionId: String,
        targetPageId: String,
        after: Boolean,
    ): Boolean {
        val target = findSection(targetSectionId) ?: return false
        val targetIndex = target.pages.indexOfFirst { it.id == targetPageId }
        if (targetIndex < 0) return false
        return movePage(sourceSectionId, pageId, targetSectionId, targetIndex + if (after) 1 else 0)
    }

    fun copyPage(
        sourceSectionId: String,
        pageId: String,
        targetSectionId: String,
        index: Int = Int.MAX_VALUE,
    ): PageCopyMapping? {
        val source = page(sourceSectionId, pageId) ?: return null
        val target = findSection(targetSectionId) ?: return null
        val copiedId = nextId("page")
        val copied =
            source.copy(
                id = copiedId,
                viewSettings = source.viewSettings.copy(),
                pdfPage = source.pdfPage?.copy(sourceOwnerPageId = copiedId),
            )
        target.pages.add(index.coerceIn(0, target.pages.size), copied)
        if (pagePaneMode == PagePaneMode.CURRENT_SECTION) focusedSectionId = targetSectionId
        selectedPageSectionId = targetSectionId
        selectedPageId = copied.id
        return PageCopyMapping(source, copied)
    }

    fun copyPageFrom(
        source: NotePage,
        targetSectionId: String,
        index: Int = Int.MAX_VALUE,
    ): PageCopyMapping? {
        val target = findSection(targetSectionId) ?: return null
        val copiedId = nextId("page")
        val copied =
            source.copy(
                id = copiedId,
                viewSettings = source.viewSettings.copy(),
                pdfPage = source.pdfPage?.copy(sourceOwnerPageId = copiedId),
            )
        target.pages.add(index.coerceIn(0, target.pages.size), copied)
        if (pagePaneMode == PagePaneMode.CURRENT_SECTION) focusedSectionId = targetSectionId
        selectedPageSectionId = targetSectionId
        selectedPageId = copied.id
        return PageCopyMapping(source, copied)
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

    private fun locateSection(id: String): SectionPosition? {
        fun search(nodes: MutableList<SectionNode>, parentId: String?): SectionPosition? {
            for (index in nodes.indices) {
                val node = nodes[index]
                if (node.id == id) return SectionPosition(nodes, index, parentId)
                search(node.children, node.id)?.let { return it }
            }
            return null
        }
        return search(sections, null)
    }

    private fun nextId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("title", title)
            put("expanded", expanded)
            put("pagePaneMode", pagePaneMode.name)
            put("focusedSectionId", focusedSectionId ?: JSONObject.NULL)
            put("selectedPageSectionId", selectedPageSectionId)
            put("selectedPageId", selectedPageId)
            put("sections", sections.toJson())
        }

    companion object {
        fun fromJson(json: JSONObject): NotebookWorkspace {
            val workspace =
                NotebookWorkspace(
                    id = json.getString("id"),
                    title = json.optString("title", "笔记本"),
                    sections = json.optJSONArray("sections").toSections(),
                    expanded = json.optBoolean("expanded", true),
                )
            workspace.pagePaneMode =
                runCatching { PagePaneMode.valueOf(json.optString("pagePaneMode", PagePaneMode.CURRENT_SECTION.name)) }
                    .getOrDefault(PagePaneMode.CURRENT_SECTION)
            workspace.focusedSectionId =
                if (json.isNull("focusedSectionId")) null
                else json.optString("focusedSectionId").takeIf { workspace.findSection(it) != null }
            workspace.selectedPageSectionId = json.optString("selectedPageSectionId")
            workspace.selectedPageId = json.optString("selectedPageId")
            if (workspace.selectedPage() == null) workspace.selectFirstPageForMode()
            return workspace
        }

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
    var activeNotebookId: String = notebooks.firstOrNull()?.id ?: error("Notebook library cannot be empty")
        private set

    fun activeNotebook(): NotebookWorkspace =
        notebooks.firstOrNull { it.id == activeNotebookId } ?: error("Active notebook is missing")

    fun captureLocation(): WorkspaceLocation = activeNotebook().captureLocation()

    fun restoreLocation(location: WorkspaceLocation): Boolean {
        if (!selectNotebook(location.notebookId)) return false
        return activeNotebook().restoreLocation(location)
    }

    fun selectNotebook(id: String): Boolean {
        if (notebooks.none { it.id == id }) return false
        activeNotebookId = id
        return true
    }

    fun addNotebook(title: String = "新笔记本"): NotebookWorkspace {
        val inheritedMode = activeNotebook().pagePaneMode
        val notebook =
            NotebookWorkspace(
                id = "notebook-${UUID.randomUUID()}",
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

    fun toJsonString(): String =
        JSONObject()
            .put("version", 1)
            .put("activeNotebookId", activeNotebookId)
            .put("notebooks", JSONArray().apply { notebooks.forEach { put(it.toJson()) } })
            .toString()

    companion object {
        fun fromJson(value: String): NotebookLibrary? =
            runCatching {
                val root = JSONObject(value)
                val source = root.getJSONArray("notebooks")
                val notebooks = MutableList(source.length()) { index -> NotebookWorkspace.fromJson(source.getJSONObject(index)) }
                require(notebooks.isNotEmpty())
                NotebookLibrary(notebooks).also { library ->
                    root.optString("activeNotebookId").takeIf(String::isNotEmpty)?.let(library::selectNotebook)
                }
            }.getOrNull()

        fun sample(): NotebookLibrary =
            NotebookLibrary(
                mutableListOf(
                    NotebookWorkspace.sample(),
                ),
            )
    }
}

private data class SectionPosition(
    val siblings: MutableList<SectionNode>,
    val index: Int,
    val parentId: String?,
)

private fun SectionNode.sectionIds(): Set<String> =
    buildSet {
        fun visit(section: SectionNode) {
            add(section.id)
            section.children.forEach(::visit)
        }
        visit(this@sectionIds)
    }

private fun SectionNode.allPages(): List<NotePage> =
    buildList {
        fun visit(section: SectionNode) {
            addAll(section.pages)
            section.children.forEach(::visit)
        }
        visit(this@allPages)
    }

private fun List<SectionNode>.toJson(): JSONArray =
    JSONArray().apply {
        this@toJson.forEach { section ->
            put(
                JSONObject()
                    .put("id", section.id)
                    .put("title", section.title)
                    .put("expanded", section.expanded)
                    .put(
                        "pages",
                        JSONArray().apply {
                            section.pages.forEach { page ->
                                put(
                                    JSONObject()
                                        .put("id", page.id)
                                        .put("title", page.title)
                                        .put("subtitle", page.subtitle)
                                        .put("viewSettings", page.viewSettings.toJson())
                                        .apply {
                                            page.pdfPage?.let { pdf ->
                                                put(
                                                    "pdfPage",
                                                    JSONObject()
                                                        .put("sourceOwnerPageId", pdf.sourceOwnerPageId)
                                                        .put("sourceAssetName", pdf.sourceAssetName)
                                                        .put("renderedAssetName", pdf.renderedAssetName)
                                                        .put("sourceDocumentName", pdf.sourceDocumentName)
                                                        .put("pageNumber", pdf.pageNumber)
                                                        .put("pageCount", pdf.pageCount)
                                                        .put("widthPoints", pdf.widthPoints.toDouble())
                                                        .put("heightPoints", pdf.heightPoints.toDouble()),
                                                )
                                            }
                                        },
                                )
                            }
                        },
                    )
                    .put("children", section.children.toJson()),
            )
        }
    }

private fun JSONArray?.toSections(): MutableList<SectionNode> {
    if (this == null) return mutableListOf()
    return MutableList(length()) { index ->
        val json = getJSONObject(index)
        val pagesJson = json.optJSONArray("pages") ?: JSONArray()
        SectionNode(
            id = json.getString("id"),
            title = json.optString("title", "分区"),
            pages =
                MutableList(pagesJson.length()) { pageIndex ->
                    val page = pagesJson.getJSONObject(pageIndex)
                    val pdf = page.optJSONObject("pdfPage")
                    NotePage(
                        id = page.getString("id"),
                        title = page.optString("title", "无标题页"),
                        subtitle = page.optString("subtitle", "刚刚"),
                        viewSettings = page.optJSONObject("viewSettings")?.let(PageViewSettings::fromJson) ?: PageViewSettings(),
                        pdfPage =
                            pdf?.let {
                                PdfPageInfo(
                                    sourceOwnerPageId = it.getString("sourceOwnerPageId"),
                                    sourceAssetName = it.getString("sourceAssetName"),
                                    renderedAssetName = it.getString("renderedAssetName"),
                                    sourceDocumentName = it.getString("sourceDocumentName"),
                                    pageNumber = it.getInt("pageNumber"),
                                    pageCount = it.getInt("pageCount"),
                                    widthPoints = it.getDouble("widthPoints").toFloat(),
                                    heightPoints = it.getDouble("heightPoints").toFloat(),
                                )
                            },
                    )
                },
            children = json.optJSONArray("children").toSections(),
            expanded = json.optBoolean("expanded", true),
        )
    }
}
