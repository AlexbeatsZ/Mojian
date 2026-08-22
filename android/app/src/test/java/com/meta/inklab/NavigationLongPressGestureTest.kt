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
    fun releasedLongPressWithoutDragOpensMenu() {
        val gesture = NavigationLongPressGesture()

        gesture.arm()

        assertEquals(NavigationReleaseAction.MENU, gesture.release())
    }

    @Test
    fun successfulDragReleaseNeverOpensMenu() {
        val gesture = NavigationLongPressGesture()

        gesture.arm()
        gesture.recordDragStart(started = true)

        assertEquals(NavigationReleaseAction.DROP_ONLY, gesture.release())
    }

    @Test
    fun failedDragStartStillBehavesAsReleasedLongPress() {
        val gesture = NavigationLongPressGesture()

        gesture.arm()
        gesture.recordDragStart(started = false)

        assertEquals(NavigationReleaseAction.MENU, gesture.release())
    }
}
