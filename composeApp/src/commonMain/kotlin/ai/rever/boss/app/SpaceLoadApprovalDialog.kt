package ai.rever.boss.app

import ai.rever.boss.components.dialogs.ConfirmationDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Asks the operator before an externally requested Space starts its terminal commands.
 *
 * Armed after the same interval [TerminalCommandApprovalDialog] uses, so a click already in
 * flight when the prompt appears cannot land on its confirm button.
 */
@Composable
internal fun SpaceLoadApprovalDialog(
    request: PendingSpaceLoad,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    key(request) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            armed = true
        }
        ConfirmationDialog(
            title = "Load this Space and run its commands?",
            message = spaceLoadApprovalMessage(request),
            confirmText = "Load and run",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}

/** The prompt's body: where the request points and every command, numbered, in full. */
internal fun spaceLoadApprovalMessage(request: PendingSpaceLoad): String =
    buildString {
        append("BOSS was asked from outside the app to load the Space \"")
        append(request.workspace.name)
        append("\" from:\n")
        append(request.workspacePath)
        append("\n\nIts terminal tabs would run these commands. Nothing has loaded or run. ")
        append("Confirm only if you recognise them:\n")
        request.commands.forEachIndexed { index, command ->
            append("\n")
            append(index + 1)
            append(". ")
            append(command)
        }
    }
