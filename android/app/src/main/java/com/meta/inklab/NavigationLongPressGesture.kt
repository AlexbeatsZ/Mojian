package com.meta.inklab

enum class NavigationReleaseAction {
    CLICK,
    DROP_ONLY,
    CONSUMED,
}

object NavigationMenuCatalog {
    val sectionItems =
        listOf(
            "重命名",
            "导出…",
            "删除",
            "移动或复制…",
            "与其他分区合并…",
            "复制指向分区的链接",
            "新建分区",
        )

    val pageItems =
        listOf(
            "重命名",
            "删除",
            "剪切",
            "复制",
            "粘贴",
            "复制指向页面的链接",
            "移动或复制…",
        )
}

class NavigationLongPressGesture {
    var isDragArmed: Boolean = false
        private set

    var isDragging: Boolean = false
        private set

    var isMenuShown: Boolean = false
        private set

    fun reset() {
        isDragArmed = false
        isDragging = false
        isMenuShown = false
    }

    fun armDrag() {
        if (!isMenuShown) isDragArmed = true
    }

    fun showMenu(): Boolean {
        if (isDragging || isMenuShown) return false
        isMenuShown = true
        isDragArmed = false
        return true
    }

    fun recordDragStart(started: Boolean) {
        if (isDragArmed && !isMenuShown && started) isDragging = true
    }

    fun release(): NavigationReleaseAction {
        val action =
            when {
                isDragging -> NavigationReleaseAction.DROP_ONLY
                isMenuShown -> NavigationReleaseAction.CONSUMED
                else -> NavigationReleaseAction.CLICK
            }
        reset()
        return action
    }
}
