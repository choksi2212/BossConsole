package ai.rever.boss.window

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WindowManagerTest {
    private val createdWindowIds = mutableListOf<String>()

    private data class TestTabInfo(
        override val id: String = "test-tab-id",
        override val typeId: TabTypeId = TabTypeId("test"),
        override val title: String = "Test Tab",
    ) : TabInfo {
        override val icon: ImageVector get() = Icons.Outlined.Language
    }

    private fun testProject(name: String = "test-proj") =
        Project(
            name = name,
            path = "/tmp/$name",
            lastOpened = 0L,
        )

    @AfterTest
    fun cleanup() {
        createdWindowIds.forEach { id ->
            WindowManager.closeWindow(id)
        }
        createdWindowIds.clear()
    }

    @Test
    fun closeWindow_removesWindow_andCleansUpUnconsumedPendingTab() {
        val initialCount = WindowManager.windowCount
        val tab = TestTabInfo(id = "tab-unconsumed")
        val window = WindowManager.createNewWindowWithTab(tab)
        createdWindowIds.add(window.id)

        assertEquals(initialCount + 1, WindowManager.windowCount)
        assertNotNull(WindowManager.getWindow(window.id))

        // When closed without consuming the pending tab, the pending entry must not leak
        WindowManager.closeWindow(window.id)

        assertEquals(initialCount, WindowManager.windowCount)
        assertNull(WindowManager.getWindow(window.id))
        assertNull(
            WindowManager.consumePendingTab(window.id),
            "Unconsumed pending tab must be removed when window is closed",
        )
    }

    @Test
    fun closeWindow_removesWindow_andCleansUpUnconsumedPendingProject() {
        val initialCount = WindowManager.windowCount
        val project = testProject("project-unconsumed")
        val window = WindowManager.createNewWindowWithProject(project)
        createdWindowIds.add(window.id)

        assertEquals(initialCount + 1, WindowManager.windowCount)
        assertNotNull(WindowManager.getWindow(window.id))

        // When closed without consuming the pending project, the pending entry must not leak
        WindowManager.closeWindow(window.id)

        assertEquals(initialCount, WindowManager.windowCount)
        assertNull(WindowManager.getWindow(window.id))
        assertNull(
            WindowManager.consumePendingProject(window.id),
            "Unconsumed pending project must be removed when window is closed",
        )
    }

    @Test
    fun consumePendingTab_beforeClose_isSafeAndLeavesNoStrandedState() {
        val tab = TestTabInfo(id = "tab-consumed")
        val window = WindowManager.createNewWindowWithTab(tab)
        createdWindowIds.add(window.id)

        val consumed = WindowManager.consumePendingTab(window.id)
        assertEquals(tab, consumed)

        WindowManager.closeWindow(window.id)
        assertNull(WindowManager.getWindow(window.id))
        assertNull(WindowManager.consumePendingTab(window.id))
    }

    @Test
    fun consumePendingProject_beforeClose_isSafeAndLeavesNoStrandedState() {
        val project = testProject("project-consumed")
        val window = WindowManager.createNewWindowWithProject(project)
        createdWindowIds.add(window.id)

        val consumed = WindowManager.consumePendingProject(window.id)
        assertEquals(project, consumed)

        WindowManager.closeWindow(window.id)
        assertNull(WindowManager.getWindow(window.id))
        assertNull(WindowManager.consumePendingProject(window.id))
    }

    @Test
    fun closeWindow_withUnknownId_isSafeNoOp() {
        val initialCount = WindowManager.windowCount
        WindowManager.closeWindow("unknown-window-id-9999")
        assertEquals(initialCount, WindowManager.windowCount)
    }
}
