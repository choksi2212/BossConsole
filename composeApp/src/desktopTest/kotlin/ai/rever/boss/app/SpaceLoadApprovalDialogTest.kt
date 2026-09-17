package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceLoadApprovalDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val request =
        PendingSpaceLoad(
            workspace =
                LayoutWorkspace(
                    name = "Shared",
                    description = "",
                    layout = SinglePanel(PanelConfig("main", emptyList())),
                ),
            workspacePath = "/tmp/shared/space.json",
            commands = listOf("echo one", "cd {projectPath} && echo two"),
        )

    @Test
    fun `the prompt names the file and shows every command in full`() {
        val message = spaceLoadApprovalMessage(request)

        assertTrue("/tmp/shared/space.json" in message, message)
        assertTrue("\"Shared\"" in message, message)
        assertTrue("1. echo one" in message, message)
        assertTrue("2. cd {projectPath} && echo two" in message, message)
    }

    @Test
    fun `a click already in flight cannot confirm the load`() {
        var confirmations = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpaceLoadApprovalDialog(request = request, onDismiss = {}, onConfirm = { confirmations++ })
        }

        rule.onNodeWithText("Load and run").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(0, confirmations) }

        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Load and run").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, confirmations) }
    }
}
