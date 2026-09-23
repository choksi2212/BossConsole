package ai.rever.boss.app

import ai.rever.boss.components.events.PluginActionEventBus
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginActionApprovalDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `next identical request cannot be approved by an immediate second click`() {
        val queue = PluginActionApprovalQueue()
        queue.enqueue(PendingPluginAction("my.plugin", "sync", emptyMap()))
        queue.enqueue(PendingPluginAction("my.plugin", "sync", emptyMap()))
        var dispatches = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            queue.current?.let { pending ->
                PluginActionApprovalDialog(
                    request = pending,
                    pendingCount = queue.size,
                    onDismiss = { queue.consume(pending) },
                    onConfirm = { if (queue.consume(pending)) dispatches++ },
                )
            }
        }
        rule.onNodeWithText("Run action").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Run action").assertIsEnabled().performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Run action").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, dispatches) }
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithText("Run action").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Run action").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(2, dispatches)
            assertNull(queue.current)
        }
    }

    @Test
    fun `title counts requests still retained on the bus, not just the one shown`() {
        PluginActionEventBus.clearForTest()
        try {
            // A window holds only the request it shows, so its queue is 1 while three more wait.
            val queue = PluginActionApprovalQueue()
            queue.enqueue(PendingPluginAction("my.plugin", "shown", emptyMap()))
            repeat(3) {
                PluginActionEventBus.requestConfirmation("my.plugin", "waiting-$it", emptyMap(), sourceWindowId = null)
            }
            rule.setContent {
                queue.current?.let { pending ->
                    PluginActionApprovalDialog(
                        request = pending,
                        pendingCount = pluginActionBacklog(queue),
                        onDismiss = {},
                        onConfirm = {},
                    )
                }
            }
            rule.onNodeWithText("Run this plugin action? (4 pending)").assertExists()

            // Another window claiming one of the retained requests shrinks the backlog shown here.
            rule.runOnIdle {
                PluginActionEventBus.claim(PluginActionEventBus.confirmEventsSnapshotForTest().first())
            }
            rule.onNodeWithText("Run this plugin action? (3 pending)").assertExists()
        } finally {
            PluginActionEventBus.clearForTest()
        }
    }
}
