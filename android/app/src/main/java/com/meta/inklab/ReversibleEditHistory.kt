package com.meta.inklab

/** The ink owner's chronological undo stream also carries non-ink edits. */
class ReversibleEditHistory {
    data class Edit(val undo: () -> Unit, val redo: () -> Unit)
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    fun record(edit: Edit) {
        redo.clear()
        undo.addLast(edit)
        if (undo.size > 128) undo.removeFirst()
    }
    fun undo(fallback: () -> Edit? = { null }): Boolean {
        val edit = undo.removeLastOrNull() ?: fallback() ?: return false
        edit.undo(); redo.addLast(edit); return true
    }
    fun redo(): Boolean {
        val edit = redo.removeLastOrNull() ?: return false
        edit.redo(); undo.addLast(edit); return true
    }
    fun clear() { undo.clear(); redo.clear() }
}
