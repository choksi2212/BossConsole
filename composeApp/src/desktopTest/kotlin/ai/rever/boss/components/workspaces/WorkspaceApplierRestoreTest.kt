package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.composer.ComposerTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabType
import ai.rever.boss.plugin.tab.diff.DiffTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the RESTORE side of the layout round trip: [createTabFromWorkspaceConfig]
 * over a saved [TabConfig] tree.
 *
 * [WorkspaceExtractorTest] covers the extract side; this test exists because
 * the two sides are separate decisions and used to disagree. A blank diff
 * path used to restore as a diff tab that can never show anything, while the
 * composer branch refused a blank session id and the extractor refused to
 * persist a blank path - three answers to "no scope". All three must agree
 * on "no scope, no tab".
 */
class WorkspaceApplierRestoreTest {
    private val tabRegistry =
        TabRegistry().apply {
            listOf(CodeEditorTabType, DiffTabType, ComposerTabType).forEach { type ->
                registerTabType(type) { _, _ -> throw UnsupportedOperationException("not used by restore") }
            }
        }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    private fun restore(tabConfig: TabConfig): TabInfo? =
        createTabFromWorkspaceConfig(
            tabConfig = tabConfig,
            resolvedProjectPath = "/tmp/proj",
            splitViewState = SplitViewState(tabRegistry, windowId = "restore-test"),
        )

    // ==================== diff tabs ====================

    @Test
    fun `a saved file diff restores as a working tree diff of that file`() {
        val tab =
            restore(
                TabConfig(
                    type = "diff",
                    title = "main.kt",
                    filePath = "src/main.kt",
                ),
            )

        val diff = assertIs<DiffTabInfo>(tab)
        assertEquals("src/main.kt", diff.filePath)
        assertTrue(
            !diff.staged,
            "the extractor only persists working-tree diffs, so restore must not invent staged ones",
        )
        assertNull(diff.fromRef)
        assertNull(diff.toRef)
    }

    @Test
    fun `a blank diff path restores no tab`() {
        // A corrupt or hand-edited layout: no scope, no tab. Agreeing with the
        // composer branch (blank session id) and the extractor (refuses to
        // persist a blank path) is the contract this test exists for.
        val tab =
            restore(
                TabConfig(
                    type = "diff",
                    title = "Diff",
                    filePath = "",
                ),
            )
        assertNull(tab, "a diff tab with no scope can never show anything; it must not be restored")
    }

    @Test
    fun `a null diff path restores no tab`() {
        val tab = restore(TabConfig(type = "diff", title = "Diff"))
        assertNull(tab)
    }

    // ==================== composer tabs ====================

    @Test
    fun `a saved composer tab restores with its session id`() {
        val tab =
            restore(
                TabConfig(
                    type = "composer",
                    title = "Composer",
                    filePath = "session-abc123",
                ),
            )

        val composer = assertIs<ComposerTabInfo>(tab)
        assertEquals("session-abc123", composer.sessionId)
    }

    @Test
    fun `a blank composer session id restores no tab`() {
        // The branch the diff one used to disagree with; the round trip only
        // holds if both sides drop the scopeless tab.
        val tab =
            restore(
                TabConfig(
                    type = "composer",
                    title = "Composer",
                    filePath = "",
                ),
            )
        assertNull(tab, "a composer tab with no session id cannot reload a session")
    }

    @Test
    fun `an unknown tab type restores nothing rather than crashing the layout`() {
        val tab = restore(TabConfig(type = "mystery", title = "?"))
        assertNull(tab)
    }

    // ===========================================================================
    // Regression for #1211: one unrestorable first tab silently dropped every
    // restorable tab behind it in a split subtree.
    //
    // The previous applyWorkspaceNode gated the WHOLE right subtree on whether the
    // FIRST tab resolved. A saved pane of [unknown, editor] therefore lost the
    // editor on restart - even though [editor, unknown] restored it. The split
    // must be skipped only when the subtree contains NO restorable tab, and every
    // restorable tab must land individually once the split exists.
    //
    // The tests below build a real workspace with a right pane of [unknown,
    // editor] / [editor, unknown] / [unknown, unknown] fixtures and pin the
    // restore behaviour for each.
    // ===========================================================================

    private object Tab1211Type : TabTypeInfo {
        override val typeId = TabTypeId("unrestorable-test", "test.plugin")
        override val displayName = "Unrestorable Test"
        override val icon = Icons.Outlined.Language
    }

    private class Tab1211Component(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo = Tab1211Type,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() {
            // Fixture; the applier only exercises tab creation and pin counting.
        }
    }

    /** A registry where "unknown" type tabs cannot resolve but editor / terminal can. */
    private val tab1211Registry =
        TabRegistry().apply {
            listOf(TerminalTabType, CodeEditorTabType, FluckTabType).forEach { type ->
                registerTabType(type) { config, ctx -> Tab1211Component(ctx, config) }
            }
            // Deliberately do NOT register Tab1211Type — so `type = "unknown"` falls
            // through to the `else -> null` arm of `createTabFromWorkspaceConfig`.
        }

    private fun newRestoreSplitViewState() = SplitViewState(tab1211Registry, windowId = "restore-1211-window")

    private fun workspaceWithRightPane(tabs: List<TabConfig>): LayoutWorkspace =
        LayoutWorkspace(
            id = "split-right",
            name = "Split with a right pane",
            description = "Outer vertical split with a right pane of tab configs",
            layout =
                VerticalSplit(
                    left =
                        SinglePanel(
                            PanelConfig(
                                id = "main",
                                tabs = listOf(TabConfig("terminal", "Main Term")),
                                pinnedCount = 0,
                            ),
                        ),
                    right =
                        SinglePanel(
                            PanelConfig(
                                id = "right",
                                tabs = tabs,
                                pinnedCount = tabs.size.coerceAtMost(1),
                            ),
                        ),
                ),
        )

    private fun rightPaneTabs(state: SplitViewState): List<String> =
        // applyWorkspace mints the right pane's id, so find it by position rather than the
        // configured id (the test's right pane happens to be the one that is NOT "main").
        state
            .getAllPanels()
            .firstOrNull { it.id != "main" }
            ?.tabsComponent
            ?.tabsState
            ?.value
            ?.tabs
            ?.map { it.title } ?: emptyList()

    @Test
    fun `unknown first tab does not drop a restorable editor behind it`() =
        runBlocking {
            // [unknown, editor] in the right pane. The old code gated the whole
            // subtree on whether the first tab resolved; the editor was dropped on
            // every restart. The fix walks the subtree for the first restorable tab.
            val state = newRestoreSplitViewState()
            applyWorkspace(
                workspaceWithRightPane(
                    listOf(
                        TabConfig("unknown", "Unknown 1"),
                        TabConfig("editor", "Editor 1"),
                    ),
                ),
                state,
                windowProjectState = null,
            )

            assertEquals(
                listOf("Editor 1"),
                rightPaneTabs(state),
                "an unknown first tab must not silently drop the restorable tab behind it",
            )
        }

    @Test
    fun `unknown trailing tab does not drop the restorable editor before it`() =
        runBlocking {
            // The mirror case. Both panes must restore, regardless of which slot the
            // unknown tab occupies.
            val state = newRestoreSplitViewState()
            applyWorkspace(
                workspaceWithRightPane(
                    listOf(
                        TabConfig("editor", "Editor 1"),
                        TabConfig("unknown", "Unknown 1"),
                    ),
                ),
                state,
                windowProjectState = null,
            )

            assertEquals(
                listOf("Editor 1"),
                rightPaneTabs(state),
                "an unknown trailing tab must not affect the restorable tabs before it",
            )
        }

    @Test
    fun `every unknown tab in a pane skips the split without leaving a ghost panel`() =
        runBlocking {
            // [unknown, unknown] in the right pane. Nothing restorable, so the split
            // is refused (the whole reason the gate exists) - the right pane simply
            // does not exist, and the left pane is alone. The old code created a
            // ghost right panel via splitPanel(tabToMove = null) in this case; this
            // test exists to pin the empty-apply contract.
            val state = newRestoreSplitViewState()
            applyWorkspace(
                workspaceWithRightPane(
                    listOf(
                        TabConfig("unknown", "Unknown 1"),
                        TabConfig("unknown", "Unknown 2"),
                    ),
                ),
                state,
                windowProjectState = null,
            )

            val panelIds = state.getAllPanels().map { it.id }.toSet()
            assertEquals(
                setOf("main"),
                panelIds,
                "a subtree with no restorable tab must not produce a ghost split",
            )
        }

    @Test
    fun `every restorable tab in a mixed pane lands in the new right panel`() =
        runBlocking {
            // [editor, unknown, terminal, unknown] - two restorable tabs separated by
            // two unknowns. The old code gated on the FIRST tab; the first unknown
            // would have dropped everything.
            val state = newRestoreSplitViewState()
            applyWorkspace(
                workspaceWithRightPane(
                    listOf(
                        TabConfig("editor", "Editor 1"),
                        TabConfig("unknown", "Unknown 1"),
                        TabConfig("terminal", "Terminal 1"),
                        TabConfig("unknown", "Unknown 2"),
                    ),
                ),
                state,
                windowProjectState = null,
            )

            assertEquals(
                listOf("Editor 1", "Terminal 1"),
                rightPaneTabs(state),
                "every restorable tab in a mixed pane must land, with unknown tabs skipped",
            )
        }
}
