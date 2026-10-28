package com.meta.inklab

internal class SceneOrderCache<T> {
    private var source: Any? = null
    private var contentRevision = Long.MIN_VALUE
    private var strokeRevision = Long.MIN_VALUE
    private var values = emptyList<T>()

    fun get(source: Any, contentRevision: Long, strokeRevision: Long, prepare: () -> List<T>): List<T> {
        if (this.source !== source || this.contentRevision != contentRevision || this.strokeRevision != strokeRevision) {
            val prepared = prepare()
            this.source = source
            this.contentRevision = contentRevision
            this.strokeRevision = strokeRevision
            values = prepared
        }
        return values
    }
}
