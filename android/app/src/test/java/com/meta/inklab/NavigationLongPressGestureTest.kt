package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationLongPressGestureTest {
    @Test
    fun sectionAndPageMenusKeepTheRequestedCommandsAndOrder() {
        assertEquals(
            listOf("重命名", "导出…", "删除", "移动或复制…", "与其他分区合并…", "复制指向分区的链接", "新建分区"),
            NavigationMenuCatalog.sectionItems,
        )
        assertEquals(
            listOf("重命名", "删除", "剪切", "复制", "粘贴", "复制指向页面的链接", "移动或复制…"),
            NavigationMenuCatalog.pageItems,
        )
    }

    @Test
    fun reachingMenuThresholdShowsMenuBeforeRelease() {
        val gesture = NavigationLongPressGesture()

        assertEquals(true, gesture.showMenu())

        assertEquals(NavigationReleaseAction.CONSUMED, gesture.release())
    }

    @Test
    fun successfulDragReleaseNeverOpensMenu() {
        val gesture = NavigationLongPressGesture()

        gesture.armDrag()
        gesture.recordDragStart(started = true)

        assertEquals(NavigationReleaseAction.DROP_ONLY, gesture.release())
    }

    @Test
    fun failedEarlyDragStartStillBehavesAsClickUntilMenuThreshold() {
        val gesture = NavigationLongPressGesture()

        gesture.armDrag()
        gesture.recordDragStart(started = false)

        assertEquals(NavigationReleaseAction.CLICK, gesture.release())
    }

    @Test
    fun menuAndDragAreMutuallyExclusive() {
        val gesture = NavigationLongPressGesture()
        gesture.armDrag()
        assertEquals(true, gesture.showMenu())
        gesture.recordDragStart(started = true)

        assertEquals(false, gesture.isDragging)
        assertEquals(NavigationReleaseAction.CONSUMED, gesture.release())
    }
}
