package ai.rever.boss.components.workspaces

import ai.rever.boss.run.ShellUtils

actual object CommandProcessor {
    actual fun normalizeCommand(command: String): String {
        // On Windows, replace " && " with "; " for PowerShell/CMD compatibility.
        // The Unix branch is unchanged - "&&" is a real shell operator there.
        if (!ShellUtils.isWindows) return command
        return windowsNormalizeCommand(command)
    }

    actual fun quotePath(path: String): String {
        // Assumes PowerShell on Windows (TerminalSettings.windowsShell defaults to
        // "powershell"), the SAME assumption normalizeCommand makes with its ";"
        // separator. Under the opt-in cmd.exe shell both are wrong together — a
        // pre-existing, shared gap, not introduced here.
        return if (ShellUtils.isWindows) {
            ShellPathQuoting.powershell(path)
        } else {
            ShellPathQuoting.posix(path)
        }
    }
}

/**
 * Quote-aware replacement of ` && ` with `; ` for PowerShell/CMD compatibility.
 *
 * `processPlaceholders` substitutes a shell-quoted `{projectPath}` and THEN runs the
 * normaliser; on Windows, the previous `String.replace(" && ", "; ")` rewrote the
 * substring inside the single quotes, so a project at `'C:\A && B\proj'` (legal
 * Windows filename) came back as `'C:\A ; B\proj'` - a nonexistent directory. A small
 * state machine skips content inside `'...'` and `"..."` so quoted regions survive
 * intact. An unterminated quote runs to end-of-input rather than half-eating the rest
 * of the command. `&&` without surrounding spaces is left alone (logical operator
 * without spacing) so `foo&&bar` survives verbatim.
 *
 * Package-private so the test suite can exercise the Windows branch on any host.
 */
internal fun windowsNormalizeCommand(command: String): String {
    val out = StringBuilder(command.length)
    var i = 0
    var inSingle = false
    var inDouble = false
    while (i < command.length) {
        val c = command[i]
        when {
            c == '\'' && !inDouble -> inSingle = !inSingle
            c == '"' && !inSingle -> inDouble = !inDouble
            !inSingle && !inDouble &&
                c == '&' && i + 2 < command.length &&
                command[i + 1] == '&' && command[i + 2] == ' ' -> {
                out.append("; ")
                i += 3
                continue
            }
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}
