package ai.rever.boss.components.workspaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks down the path-quoting that fixes "launch a terminal in a folder whose
 * name has a space/apostrophe" (the `AI Workflow Tools' Exports` bug, where the
 * unquoted `cd …` left the shell stuck at a `quote>` prompt).
 *
 * [ShellPathQuoting]'s two strategies are pure, so both the POSIX and PowerShell
 * branches are verified here regardless of the host OS. The end-to-end POSIX
 * round-trip is asserted too: re-parsing the quoted string yields the original.
 */
class ShellPathQuotingTest {
    // ---- POSIX (macOS/Linux) ----

    @Test
    fun posixQuotesPlainPath() {
        assertEquals("'/Users/foo/bar'", ShellPathQuoting.posix("/Users/foo/bar"))
    }

    @Test
    fun posixQuotesPathWithSpaces() {
        assertEquals("'/Users/foo/AI Workflow Tools'", ShellPathQuoting.posix("/Users/foo/AI Workflow Tools"))
    }

    @Test
    fun posixEscapesEmbeddedApostrophe() {
        // The motivating case: .../AI Workflow Tools' Exports/claude-exports
        val path = "/Users/ananya_work/AI Workflow Tools' Exports/claude-exports"
        val quoted = ShellPathQuoting.posix(path)
        assertEquals("'/Users/ananya_work/AI Workflow Tools'\\'' Exports/claude-exports'", quoted)
        // Round-trip: a POSIX shell parses the quoted string back to the exact path.
        assertEquals(path, posixUnquote(quoted))
    }

    // ---- Windows PowerShell ----

    @Test
    fun powershellQuotesBackslashPathWithSpace() {
        // Backslashes are literal inside PowerShell single quotes (no escaping).
        assertEquals("'C:\\dir with space'", ShellPathQuoting.powershell("C:\\dir with space"))
    }

    @Test
    fun powershellDoublesEmbeddedApostrophe() {
        assertEquals("'a''b'", ShellPathQuoting.powershell("a'b"))
    }

    // ---- CommandProcessor delegation (POSIX host) ----

    @Test
    fun quotePathDelegatesToPosixOnThisHost() {
        // CI runs on macOS/Linux, so the actual resolves to the POSIX strategy.
        assertEquals(ShellPathQuoting.posix("/a b"), CommandProcessor.quotePath("/a b"))
    }

    /**
     * Parse a POSIX single-quoted token back to its literal value: strip the
     * outer quotes and collapse each `'\''` escape sequence back to `'`.
     */
    private fun posixUnquote(token: String): String {
        assertTrue(token.startsWith("'") && token.endsWith("'"))
        return token.substring(1, token.length - 1).replace("'\\''", "'")
    }

    // ---- CommandProcessor.normalizeCommand: quote-aware replacement (fixes #1181) ----
    //
    // `processPlaceholders` substitutes a shell-quoted `{projectPath}` and THEN runs
    // `normalizeCommand`. On Windows, the old `String.replace(" && ", "; ")` rewrote the
    // substring inside the single quotes, so a project at `'C:\A && B\proj'` came back
    // as `'C:\A ; B\proj'` - a nonexistent directory. The fix walks the string and skips
    // content inside `'...'` / `"..."` so quoted regions survive intact.
    //
    // CI runs on a POSIX host, where `CommandProcessor.normalizeCommand` is a no-op.
    // These tests target the package-private `windowsNormalizeCommand` directly so the
    // Windows behaviour is exercised regardless of the host OS.

    @Test
    fun normalizePreservesUnquotedPlainChain() {
        // Sanity: the basic replacement is unchanged for unquoted text.
        assertEquals("cd /repo; claude", windowsNormalizeCommand("cd /repo && claude"))
    }

    @Test
    fun normalizeLeavesSpacesAroundLogicalOperatorAlone() {
        // The old code's "preserves `&&` without surrounding spaces" comment was
        // a real guard. The new state machine keeps it.
        assertEquals("foo&&bar", windowsNormalizeCommand("foo&&bar"))
    }

    @Test
    fun normalizePreservesQuotedSingleAmpAmp() {
        // The whole bug: a quoted `&&` must survive intact so the shell sees the
        // path it was given.
        assertEquals(
            "cd 'C:\\A && B\\proj'; claude",
            windowsNormalizeCommand("cd 'C:\\A && B\\proj' && claude"),
        )
    }

    @Test
    fun normalizePreservesQuotedDoubleAmpAmp() {
        // Double quotes are the other shape `quotePath` could use; the state machine
        // must skip both.
        assertEquals(
            "cd \"C:\\A && B\\proj\"; claude",
            windowsNormalizeCommand("cd \"C:\\A && B\\proj\" && claude"),
        )
    }

    @Test
    fun normalizeWalksPastQuoteInMiddleOfWord() {
        // A `&&` that straddles the closing quote is still inside it until the quote
        // closes - the original `replace` happily clobbered both. The fix walks the
        // quote.
        assertEquals(
            "echo 'x && y'; next",
            windowsNormalizeCommand("echo 'x && y' && next"),
        )
    }

    @Test
    fun normalizeHandlesUnterminatedQuoteToEnd() {
        // A pathological command with a quote that never closes still preserves the
        // content verbatim; the unquoted tail is still walked.
        assertEquals(
            "cd 'unfinished && keep && me; cmd",
            windowsNormalizeCommand("cd 'unfinished && keep && me; cmd"),
        )
    }

    @Test
    fun normalizePreservesQuoteInsideDouble() {
        // Inside double quotes a single quote is literal; the state machine must
        // still respect double-quote scope.
        assertEquals(
            "echo \"she said 'hi && there'\"; done",
            windowsNormalizeCommand("echo \"she said 'hi && there'\" && done"),
        )
    }
}
