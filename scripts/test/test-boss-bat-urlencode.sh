#!/usr/bin/env bash
# Exercise boss.bat's :urlencode subroutine against the injection
# paths reported in #1057. The script interpolates the argument into a
# single-quoted PowerShell literal in the unpatched version; a single
# quote, semicolon, or backtick in any argument closes the literal and
# runs the rest as PowerShell as the user. After the fix the argument
# is passed via an environment variable, so PowerShell sees only a
# string and never code.
#
# This test runs on Windows only; the unix boss shim has its own test
# in test-headless-cli.sh and uses a different mechanism.

set -euo pipefail

if ! command -v cmd.exe >/dev/null 2>&1; then
    echo "skip: boss.bat is Windows-only; cmd.exe not found on this runner"
    exit 0
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
boss_bat_win="$(cygpath -w "$root/scripts/boss.bat")"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

# The marker file the injected command would create. The test passes
# iff it is absent after every run.
marker_win="$(cygpath -w "$scratch/pwned.marker")"
marker_bash="$scratch/pwned.marker"

# Emit a test batch file that invokes boss.bat with the given argument,
# then run it via cmd.exe. Bash quoting of arguments that contain single
# quotes is otherwise hopeless, so each case is written as a complete
# batch file with no shell substitution at call time.
emit_test_bat() {
    local arg="$1"
    cat > "$scratch/test-boss-bat-urlencode.bat" <<EOF
@echo off
setlocal
if exist "${marker_win}" del "${marker_win}"
call "${boss_bat_win}" file ${arg}
endlocal
EOF
    cmd //C "$(cygpath -w "$scratch/test-boss-bat-urlencode.bat")" > /dev/null 2>&1 || true
}

expect_no_marker() {
    local label="$1"
    if [[ -e "$marker_bash" ]]; then
        echo "FAIL: ${label} injection created ${marker_win}" >&2
        rm -f "$marker_bash"
        exit 1
    fi
    echo "PASS: ${label} does not break out"
}

# 1. Single quote closes the PS literal and lets the rest execute.
#    The original buggy script parsed this as `EscapeDataString('')`
#    followed by an arbitrary PS statement.
emit_test_bat "\"test'); New-Item -Path '${marker_win}' -ItemType File; #'\""
expect_no_marker "single quote"

# 2. Semicolon alone (the second half of an injection, harmless on
#    its own but it is what the injected statement would use).
emit_test_bat "\"test; New-Item -Path '${marker_win}' -ItemType File\""
expect_no_marker "semicolon"

# 3. Backtick is PowerShell's escape character. The literal backtick in
#    the test batch file is a backslash-quoted backtick for cmd's parser.
emit_test_bat '"test`); New-Item -Path '"'"'${marker_win}'"'"' -ItemType File; #"'
expect_no_marker "backtick"

# 4. Bang - the old routine ran under enabledelayedexpansion and so ate
#    literal ! characters in the argument. The fix disables that for
#    the routine; verify the file does not crash and no marker appears.
emit_test_bat "\"hello!world!danger\""
expect_no_marker "exclamation"

# 5. Percent - cmd would re-expand %str%-shaped text in the unpatched
#    for/f line. %% here is doubled so cmd's parser expands it to a
#    single literal %.
emit_test_bat "\"foo%%bar%%baz\""
expect_no_marker "percent"

echo "All boss.bat :urlencode injection tests passed"
