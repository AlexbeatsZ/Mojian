package com.meta.inklab

/** The modifier controls a preview; only a normal pointer release can commit a copy. */
class SelectionMoveCopy {
    var active = false
        private set
    var preview = false
        private set

    fun begin(control: Boolean) { active = true; update(control) }
    fun update(control: Boolean) { preview = active && control }
    fun finish(commit: Boolean): Boolean {
        val copy = commit && preview
        active = false
        preview = false
        return copy
    }
}
