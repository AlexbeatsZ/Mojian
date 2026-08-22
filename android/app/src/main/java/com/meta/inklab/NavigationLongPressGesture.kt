package com.meta.inklab

enum class NavigationReleaseAction {
    CLICK,
    MENU,
    DROP_ONLY,
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
    var isArmed: Boolean = false
        private set

    var isDragging: Boolean = false
        private set

    fun reset() {
        isArmed = false
        isDragging = false
    }

    fun arm() {
        isArmed = true
    }

    fun recordDragStart(started: Boolean) {
        if (isArmed && started) isDragging = true
    }

    fun release(): NavigationReleaseAction {
        val action =
            when {
                isDragging -> NavigationReleaseAction.DROP_ONLY
                isArmed -> NavigationReleaseAction.MENU
                else -> NavigationReleaseAction.CLICK
            }
        reset()
        return action
    }
}
