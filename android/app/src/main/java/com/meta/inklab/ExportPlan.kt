package com.meta.inklab

data class ExportPlannedPage(val sourceIndex: Int, val sheet: ExportSheet, val backgroundArgb: Int)

class ExportPlan(val pages: List<ExportPlannedPage>) {
    companion object {
        fun create(sourceCount: Int, options: ExportOptions,
            describe: (Int) -> List<Pair<ExportSheet, Int>>): ExportPlan {
            val pages = buildList {
                repeat(sourceCount) { sourceIndex ->
                    describe(sourceIndex).forEach { (sheet, color) ->
                        add(ExportPlannedPage(sourceIndex, sheet, color))
                    }
                }
            }
            return ExportPlan(ExportLayout.selectPages(options.pageRange, pages.size).map(pages::get))
        }
    }
}

interface ExportPageLease : AutoCloseable {
    val source: VectorPdfSource
}

interface ExportPageProvider {
    fun plan(options: ExportOptions, outputSize: PdfOutputPageSize? = null): ExportPlan
    fun acquire(sourceIndex: Int): ExportPageLease
}

internal object ExportPageTraversal {
    fun <Resource : AutoCloseable> visit(indices: List<Int>, sourceIndex: (Int) -> Int,
        acquire: (Int) -> Resource, cancelled: () -> Boolean,
        render: (Int, Resource) -> Unit) {
        var cursor = 0
        while (cursor < indices.size) {
            if (cancelled()) throw java.util.concurrent.CancellationException()
            val source = sourceIndex(indices[cursor])
            acquire(source).use { resource ->
                while (cursor < indices.size && sourceIndex(indices[cursor]) == source) {
                    if (cancelled()) throw java.util.concurrent.CancellationException()
                    render(indices[cursor], resource)
                    cursor++
                }
            }
        }
    }
}
