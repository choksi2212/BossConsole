#!/usr/bin/env pwsh
<#
.SYNOPSIS
Regression tests for the boss.bat :urlencode and :detect_and_route subroutines (#1057, #1059).

The :urlencode subroutine used to interpolate the raw CLI argument into a
single-quoted PowerShell string literal:

    powershell -NoProfile -Command "[System.Uri]::EscapeDataString('%str%')"

so a single quote in any argument closed the literal and executed whatever
followed it. The fix routes the value through the environment instead:

    powershell -NoProfile -Command "[System.Uri]::EscapeDataString([Environment]::GetEnvironmentVariable('str'))"

:detect_and_route opens its own parse scope (#1059); a literal ! in any
argument must survive on the auto-detect path the same way it does on the
verb paths.

This harness exercises the fixed subroutines the way boss.bat calls them,
including the paren-balanced injection payload that fired on the old code
and a literal-! probe for the auto-detect path. It runs under pwsh on any
OS (the script-tests job exports BOSS_TEST_PWSH), and falls back to
asserting the source shape when cmd.exe is unavailable.
#>

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$batPath = Join-Path $repoRoot 'boss.bat'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        Write-Error "ASSERTION FAILED: $Message"
        exit 1
    }
    Write-Output "ok - $Message"
}

# --- Source-shape checks (run everywhere, no cmd.exe needed) -------------

$bat = Get-Content $batPath -Raw

Assert-True ($bat -match [regex]::Escape("EscapeDataString([Environment]::GetEnvironmentVariable('str'))")) `
    'the :urlencode shim reads the value from the environment, not from the command line'

Assert-True (-not ($bat -match [regex]::Escape("EscapeDataString('%str%')"))) `
    'the :urlencode shim no longer interpolates %str% into the PowerShell string literal'

# :detect_and_route must open DisableDelayedExpansion too (#1059): the
# function does not read !var!, and EnableDelayedExpansion here would
# re-create the literal-!-eating defect on the auto-detect path that the
# top-level DisableDelayedExpansion just fixed.
$detectScopeRegex = [regex]'(?ms):detect_and_route\s*\r?\n(?:REM[^\r\n]*\r?\n)*setlocal (Disable|Enable)DelayedExpansion'
$detectScopeMatch = $detectScopeRegex.Match($bat)
Assert-True ($detectScopeMatch.Success) ':detect_and_route is followed by a setlocal scope line'
Assert-True ($detectScopeMatch.Groups[1].Value -eq 'Disable') `
    ':detect_and_route opens DisableDelayedExpansion (EnableDelayedExpansion would eat ! in the auto-detect path)'

# --- Live behavior checks (need cmd.exe; skipped elsewhere) --------------

if ($env:OS -ne 'Windows_NT' -or -not (Get-Command cmd.exe -ErrorAction SilentlyContinue)) {
    Write-Output 'ok - cmd.exe unavailable, live :urlencode probes skipped (source-shape checks passed)'
    exit 0
}

# Build a probe batch that inlines the CURRENT :urlencode from boss.bat and
# calls it exactly the way the argumented verbs do.
$probe = Join-Path $env:TEMP ("boss-urlencode-probe-" + [guid]::NewGuid().ToString('N') + '.cmd')
$marker = Join-Path $env:TEMP ("boss-urlencode-marker-" + [guid]::NewGuid().ToString('N'))

# Extract the :urlencode block verbatim from the shipped script.
$lines = Get-Content $batPath
$startIdx = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -eq ':urlencode') { $startIdx = $i; break }
}
Assert-True ($startIdx -ge 0) ':urlencode subroutine exists in boss.bat'
# Trim the block at its own goto :eof - when inlined at the top of a probe
# script, the subroutine's own end-of-subroutine jump would skip the probe
# body entirely (batch recursion trap).
$block = ($lines[$startIdx..($lines.Count - 1)] -join "`r`n")
$eofIdx = $block.IndexOf("goto :eof")
if ($eofIdx -ge 0) { $block = $block.Substring(0, $eofIdx + "goto :eof".Length) }

# The subroutine block goes BELOW the probe calls, exactly like boss.bat:
# batch falls into labels top-down, so an inlined subroutine above the calls
# runs as straight-line code first and recurses.
$probeBody = @"
@echo off
REM Match the real boss.bat top scope: DisableDelayedExpansion so a literal
REM ! in the call argument survives intact until :urlencode (which sets up
REM its own DisableDelayedExpansion block and never reads !var!) can hand
REM the value to PowerShell.
setlocal DisableDelayedExpansion
set OUTVAR=
set MARKER=$marker
if exist "%MARKER%" del "%MARKER%"
call :urlencode "a'b!c%%d e" OUTVAR
echo PLAIN:[%OUTVAR%]
call :urlencode "x'); New-Item -ItemType File -Path ('%MARKER%'); ('" OUTVAR
echo INJECT:[%OUTVAR%]
if exist "%MARKER%" (echo INJECTION_OCCURRED) else (echo INJECTION_DEAD)
goto :done
$block
:done
endlocal
"@
Set-Content -Path $probe -Value $probeBody -Encoding Ascii

try {
    $output = & cmd.exe /c $probe 2>&1 | ForEach-Object { "$_" }

    $plain = $output | Where-Object { $_ -like 'PLAIN:*' } | Select-Object -First 1
    if ($null -eq $plain) {
        Write-Error 'ASSERTION FAILED: No PLAIN: line captured (probe did not emit the expected output)'
        exit 1
    }
    $plain = ($plain -replace '^PLAIN:\[?', '').TrimEnd(']').Trim()
    # Note: a literal % in the CALL argument collapses one batch-expansion layer
# before :urlencode sees it (call-time expansion, unchanged by this fix); the
# injection-relevant characters - quote, bang, space - must survive intact and
# the space must percent-encode. .NET 4.5+ Uri.EscapeDataString leaves the
# RFC 3986 unreserved set (A-Z a-z 0-9 - . _ ~) and these six mark characters
# alone unescaped: ' ( ) ! * - so the literal ' and ! pass through verbatim
# rather than being percent-encoded. The point of the fix is that they reach
# PowerShell as data, not as syntax.
Assert-True ($plain -like "a'b!c*%20e") "mixed quote/bang/space argument survives and encodes (got: $plain)"

    # INJECTION_DEAD / INJECTION_OCCURRED is its own bare output line, not a
    # prefix of INJECT:[...], so match against the whole captured output.
    $verdict = ($output | Where-Object { $_ -match 'INJECTION_(DEAD|OCCURRED)' })
    Assert-True ($verdict -match 'INJECTION_DEAD') 'the paren-balanced injection payload that fired on the old code is encoded as data, not executed'

    Assert-True (-not (Test-Path $marker)) 'no marker file created by the injection payload'
} finally {
    Remove-Item $probe -ErrorAction SilentlyContinue
    Remove-Item $marker -ErrorAction SilentlyContinue
}

# --- :detect_and_route probe (#1059) --------------------------------------

# Inline the CURRENT :detect_and_route from boss.bat. The function reaches
# :detect_url and :detect_domain by goto from inside, so the inlined block
# runs to end-of-file. The argument passed in ("xxxxx!yyyyy") contains a
# literal ! but no http(s)://, no TLD match and no existing file/folder,
# so :detect_and_route falls through to the "Could not detect type" branch
# which echoes the argument verbatim. With EnableDelayedExpansion on (the
# pre-#1059 default), the ! would be eaten at `set "arg=%~1"` and the echo
# would print "Error: Could not determine type for: xxxxyyyyy"; with
# DisableDelayedExpansion the ! survives and the probe passes.
$detectStartIdx = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -eq ':detect_and_route') { $detectStartIdx = $i; break }
}
Assert-True ($detectStartIdx -ge 0) ':detect_and_route subroutine exists in boss.bat'
$detectBlock = ($lines[$detectStartIdx..($lines.Count - 1)] -join "`r`n")

$detectProbe = Join-Path $env:TEMP ("boss-detect-probe-" + [guid]::NewGuid().ToString('N') + '.cmd')
$detectBody = @"
@echo off
REM Match the real boss.bat top scope: DisableDelayedExpansion so a literal
REM ! in the call argument survives intact. :detect_and_route opens its own
REM DisableDelayedExpansion block at :227 (the same scope the shipped script
REM uses), so any future switch back to EnableDelayedExpansion there would
REM re-eat the ! and this probe would fail.
setlocal DisableDelayedExpansion
call :detect_and_route "xxxxx!yyyyy"
echo ROUTE_EXIT=%ERRORLEVEL%
goto :detect_done
$detectBlock
:detect_done
endlocal
"@
Set-Content -Path $detectProbe -Value $detectBody -Encoding Ascii

try {
    $detectOutput = & cmd.exe /c $detectProbe 2>&1 | ForEach-Object { "$_" }
    $errLine = $detectOutput | Where-Object { $_ -like 'Error: Could not determine type for: *' } | Select-Object -First 1
    if ($null -eq $errLine) {
        Write-Error 'ASSERTION FAILED: No "Error: Could not determine type" line captured (probe did not reach the no-match branch)'
        exit 1
    }
    Assert-True ($errLine -like 'Error: Could not determine type for: xxxxx!yyyyy') `
        "literal ! survives the auto-detect path (got: $errLine)"
} finally {
    Remove-Item $detectProbe -ErrorAction SilentlyContinue
}

Write-Output 'ALL URLencode tests passed'
