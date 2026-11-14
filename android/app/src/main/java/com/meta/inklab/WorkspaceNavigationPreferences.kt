package com.meta.inklab

import org.json.JSONObject

/** Device-local navigation stores identities and expansion flags without duplicating page content. */
object WorkspaceNavigationPreferences {
    fun encode(library: NotebookLibrary): String = JSONObject()
        .put("activeNotebookId", library.activeNotebookId)
        .put("notebooks", JSONObject().apply {
            library.notebooks.forEach { notebook ->
                put(notebook.id, JSONObject().put("expanded", notebook.expanded)
                    .put("focusedSectionId", notebook.focusedSectionId ?: JSONObject.NULL)
                    .put("selectedPageSectionId", notebook.selectedPageSectionId)
                    .put("selectedPageId", notebook.selectedPageId)
                    .put("sections", JSONObject().apply {
                        notebook.allSections().forEach { put(it.id, it.expanded) }
                    }))
            }
        }).toString()

    fun restore(library: NotebookLibrary, text: String?) {
        val root = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        library.notebooks.forEach { notebook ->
            val state = root.optJSONObject("notebooks")?.optJSONObject(notebook.id) ?: return@forEach
            notebook.expanded = state.optBoolean("expanded", notebook.expanded)
            notebook.allSections().forEach { section ->
                val sections = state.optJSONObject("sections")
                if (sections?.has(section.id) == true) section.expanded = sections.getBoolean(section.id)
            }
            if (state.isNull("focusedSectionId")) notebook.focusNotebook()
            else notebook.selectSection(state.optString("focusedSectionId"))
            notebook.selectPage(state.optString("selectedPageSectionId"), state.optString("selectedPageId"))
        }
        library.selectNotebook(root.optString("activeNotebookId"))
    }
}
