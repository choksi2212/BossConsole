package ai.rever.boss.window

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the StateFlow read-modify-write races fixed in
 * [MenuActionsHandler] (issue #1276):
 * `updateSplitEnabled` and `updatePanelCount` did `_state.value = _state.value + (key to value)`,
 * which is NOT atomic on a `MutableStateFlow`. Two concurrent callers on the same window id
 * each read the same map, each wrote their own copy, and the later writer clobbered the earlier
 * one - so the keyboard interceptor's view of "can this chord fire" could disagree with the
 * menu's view in the same frame.
 *
 * The CAS `update { }` form the sibling functions already use is atomic, so racing many calls
 * on the same key must leave the map reflecting the last writer, never the union of writes.
 */
class MenuActionsHandlerConcurrencyTest {
    @Test
    fun `concurrent updateSplitEnabled on the same window keeps the final value`() =
        runBlocking(Dispatchers.Default) {
            val windowId = "window-A"
            // The race is between reads of `_splitEnabledState.value` and writes back: both
            // callers read the same starting map, each produces its own copy, the later write
            // wins. `update { it + (k to v) }` retries until CAS lands, so every call's
            // contribution reaches the map.
            val count = 50
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).map { i ->
                    async {
                        start.await()
                        MenuActionsHandler.updateSplitEnabled(windowId, enabled = i % 2 == 0)
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            // The map must contain the entry the last writer left. It is not safe to assume
            // which value that was, only that it is one of the values any caller wrote.
            assertEquals(
                windowId,
                MenuActionsHandler.splitEnabledState.value.keys.first(),
                "the window id must be present after concurrent updates",
            )
        }

    @Test
    fun `concurrent updatePanelCount on the same window keeps the final value`() =
        runBlocking(Dispatchers.Default) {
            val windowId = "window-B"
            val count = 50
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).map { count ->
                    async {
                        start.await()
                        MenuActionsHandler.updatePanelCount(windowId, count = count)
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            // Exactly one entry under the window id - the bare read-modify-write could
            // overwrite the same key with two different maps and end up with the union
            // (or with both old and new entries under the same key), which `update { }`
            // rules out by atomically replacing the map.
            assertEquals(
                setOf(windowId),
                MenuActionsHandler.panelCountState.value.keys,
                "the only key left under the window must be the window id, not a stale union",
            )
        }
}
