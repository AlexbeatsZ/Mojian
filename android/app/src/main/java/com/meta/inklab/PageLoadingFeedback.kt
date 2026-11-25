package com.meta.inklab

/**
 * Visual state of the page-open feedback. Input blocking ([active]) starts immediately, but the
 * progress indicator only appears after [REVEAL_DELAY_MS] so fast page switches never flash a
 * loading layer over the paper. Failures are shown at once because they need the user's action.
 *
 * Pure state machine: the Activity supplies the main-thread scheduler and the renderer.
 */
internal class PageLoadingFeedback(
    private val schedule: (delayMs: Long, task: Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val render: (Visual) -> Unit,
) {
    enum class Visual { HIDDEN, PROGRESS, FAILED }

    var active = false
        private set
    var visual = Visual.HIDDEN
        private set
    private var revealPending = false

    private val reveal = Runnable {
        revealPending = false
        if (active && visual == Visual.HIDDEN) show(Visual.PROGRESS)
    }

    /** A page read started (or another one replaced it); keep an already visible indicator. */
    fun loading() {
        active = true
        if (visual == Visual.PROGRESS || revealPending) return
        if (visual == Visual.FAILED) show(Visual.HIDDEN)
        revealPending = true
        schedule(REVEAL_DELAY_MS, reveal)
    }

    /** The read failed and no previous page could be restored; editing stays blocked. */
    fun failed() {
        active = true
        cancelReveal()
        show(Visual.FAILED)
    }

    fun finished() {
        active = false
        cancelReveal()
        show(Visual.HIDDEN)
    }

    private fun cancelReveal() {
        if (!revealPending) return
        revealPending = false
        cancel(reveal)
    }

    private fun show(next: Visual) {
        if (visual == next) return
        visual = next
        render(next)
    }

    companion object {
        /** Typical page binds finish within this window; only slower reads show progress. */
        const val REVEAL_DELAY_MS = 250L
        const val FADE_IN_MS = 180L
        const val FADE_OUT_MS = 140L
    }
}
