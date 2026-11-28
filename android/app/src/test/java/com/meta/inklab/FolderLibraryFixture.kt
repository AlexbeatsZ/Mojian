package com.meta.inklab

import java.io.File

/** The shared v3 folder-library sample (shared/fixtures/workspace-v3) and an observable form of a library on disk. */
internal object FolderLibraryFixture {
    const val NOTEBOOK = "共享样例"
    const val SECTION = "共享样例/样例分区"
    const val PAGE = "共享样例/样例分区/黄金页面.mojian"

    fun copyTo(root: File) {
        val source = File(requireNotNull(javaClass.classLoader?.getResource("fixtures/workspace-v3")) { "v3 fixture missing" }.toURI())
        source.copyRecursively(root, overwrite = false)
        check(File(root, "$NOTEBOOK/${FolderNames.DESCRIPTOR}").isFile) { "v3 fixture descriptors were not packaged" }
    }

    /** Every synchronized file's bytes, keyed by path: what other devices see. */
    fun files(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath to it }
        .filter { (path, _) -> !path.startsWith(".mojian") }
        .associate { (path, file) -> path to file.readText() }

    /** Simulates another device adding a section to the notebook. */
    fun remoteFolder(root: File, notebook: String = NOTEBOOK) = File(root, "$notebook/另一台设备的分区").apply { check(mkdirs()) }
}
