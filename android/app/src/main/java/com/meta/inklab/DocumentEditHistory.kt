package com.meta.inklab

/** One chronological stream for a page. Navigation is deliberately absent from its snapshots. */
class DocumentEditHistory<S : Any>(
    private val capture: () -> S,
    private val restore: (S) -> Unit,
    private val capacity: Int = 80,
) {
    private data class Edit<S>(val before: S, val after: S)
    private val undo = ArrayDeque<Edit<S>>()
    private val redo = ArrayDeque<Edit<S>>()
    private var current: S? = null
    private var depth = 0
    private var pending = false
    var restoring = false
        private set

    fun reset() {
        clear()
        current = capture()
    }

    fun clear() {
        undo.clear(); redo.clear(); current = null; depth = 0; pending = false
    }

    fun begin() { depth++ }
    fun end() {
        if (depth == 0) return
        depth--
        if (depth == 0 && pending) commit()
    }

    inline fun <T> transaction(action: () -> T): T {
        begin()
        try { return action() } finally { end() }
    }

    fun changed() {
        if (restoring || current == null) return
        pending = true
        if (depth == 0) commit()
    }

    private fun commit() {
        pending = false
        val before = current ?: return
        val after = capture()
        if (before == after) return
        undo.addLast(Edit(before, after))
        while (undo.size > capacity) undo.removeFirst()
        redo.clear()
        current = after
    }

    fun undo(): Boolean {
        if (depth != 0) return false
        val edit = undo.lastOrNull() ?: return false
        apply(edit.before)
        undo.removeLast(); redo.addLast(edit)
        return true
    }

    fun redo(): Boolean {
        if (depth != 0) return false
        val edit = redo.lastOrNull() ?: return false
        apply(edit.after)
        redo.removeLast(); undo.addLast(edit)
        return true
    }

    private fun apply(snapshot: S) {
        restoring = true
        try { restore(snapshot); current = snapshot } finally { restoring = false }
    }
}
