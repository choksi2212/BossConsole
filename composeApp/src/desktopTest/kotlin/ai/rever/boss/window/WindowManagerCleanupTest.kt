package ai.rever.boss.window

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for #1224: `WindowManager.closeWindow` must drop any unconsumed
 * pending tab / project keyed to the closing window, otherwise every
 * `createNewWindowWithTab` / `createNewWindowWithProject` for a window that closes
 * before its `LaunchedEffect` consumer runs leaks one entry in those maps. The
 * maps are `ConcurrentHashMap`s, the keys are random UUIDs, and the entries
 * hold a `TabInfo` / `Project` that nothing will ever read - unbounded growth
 * over a long session.
 */
class WindowManagerCleanupTest {
    private object StubTabType : TabTypeInfo {
        override val typeId = TabTypeId("stub", "test.plugin")
        override val displayName = "Stub"
        override val icon = Icons.Outlined.Language
    }

    private data class StubTabInfo(
        override val id: String,
        override val title: String,
    ) : TabInfo {
        override val typeId: TabTypeId = StubTabType.typeId
        override val icon get() = Icons.Outlined.Language
    }

    private fun freshManager(): WindowManager = WindowManager.also {
        // WindowManager keeps static state; clear anything left over from earlier tests
        // so this test class is order-independent.
        for (w in it.windows.toList()) it.closeWindow(w.id)
    }

    @Test
    fun `closeWindow drops an unconsumed pending tab`() {
        val manager = freshManager()
        val window = manager.createNewWindow()
        val opened = manager.createNewWindowWithTab(
            initialTab = StubTabInfo(id = "leaked-tab", title = "Leaked"),
            position = null,
            windowType = WindowType.MAIN,
        )

        // Close the just-opened window before its LaunchedEffect consumer runs - the
        // exact shape of the bug, where the pending entry stays in the map.
        manager.closeWindow(opened.id)

        assertNull(
            manager.consumePendingTab(window.id),
            "an unconsumed pending tab must not leak past closeWindow",
        )
    }

    @Test
    fun `closeWindow drops an unconsumed pending project`() {
        val manager = freshManager()
        val project = Project(name = "Leaked", path = "/tmp/leaked", lastOpened = 0L)
        val window = manager.createNewWindowWithProject(
            project = project,
            position = null,
            windowType = WindowType.MAIN,
        )

        manager.closeWindow(window.id)

        assertNull(
            manager.consumePendingProject(window.id),
            "an unconsumed pending project must not leak past closeWindow",
        )
    }

    @Test
    fun `closeWindow keeps a consumed tab cleared (regression guard)`() {
        // The pending entry is *removed* by consume; the cleanup path must not put
        // anything back. This catches a regression where the cleanup accidentally
        // resurrects a stale entry.
        val manager = freshManager()
        val window = manager.createNewWindow()
        manager.createNewWindowWithTab(
            initialTab = StubTabInfo(id = "consumed-then-closed", title = "Consumed"),
            position = null,
            windowType = WindowType.MAIN,
        )
        assertNotNull(manager.consumePendingTab(window.id), "the tab should be there for consume to find")
        manager.closeWindow(window.id)
        assertNull(manager.consumePendingTab(window.id))
    }

    @Test
    fun `a closed window is gone from windows`() {
        val manager = freshManager()
        val before = manager.windows.size
        val window = manager.createNewWindow()
        assertEquals(before + 1, manager.windows.size)
        manager.closeWindow(window.id)
        assertEquals(before, manager.windows.size, "closeWindow must remove the window from the live list")
        assertTrue(window.id !in manager.windows.map { it.id })
    }

    @Test
    fun `closing an unknown window id still drops its pending entries`() {
        // A window that fails to come up (e.g. cancellation during create-window) can
        // leave pending entries behind keyed to an id no window will ever consume. The
        // cleanup has to work even when no BossWindowState is found.
        val manager = freshManager()
        val phantomId = "phantom-window-id-that-never-was"
        manager.closeWindow(phantomId) // must not throw
        assertNull(manager.consumePendingTab(phantomId))
    }
}
