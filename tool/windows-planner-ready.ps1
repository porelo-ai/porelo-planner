# The planner's own part of the MSIX install check: run by the window's install check once it has started
# porelo-planner.exe through the PATH, it waits for the planner's planner.json in the account's real
# %LOCALAPPDATA%\porelo-planner, where the window reads it, and asks /health with the token the file holds. It ends
# without error once the planner answers UP with the version PORELO_PLANNER_VERSION and the commit
# PORELO_PLANNER_COMMIT name, the installed build's, and throws naming what it asked and got otherwise. -Seconds is the
# time the install check has left; at 0 the file is still looked for once.
#Requires -Version 7.0
param(
    [ValidateRange(0, 600)]
    [int] $Seconds = 30
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# What the installed build must answer; a build from a checkout always knows its commit, so null is refused here.
$version = $env:PORELO_PLANNER_VERSION
if ("$version" -notmatch '^(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*)){2}(~snapshot\.(0|[1-9][0-9]*))?$') {
    throw "Asked for PORELO_PLANNER_VERSION as x.y.z or x.y.z~snapshot.N; got '$version'"
}
$commit = $env:PORELO_PLANNER_COMMIT
if ("$commit" -cnotmatch '^[0-9a-f]{40}$') { throw "Asked for PORELO_PLANNER_COMMIT as 40 hex digits; got '$commit'" }

$appData = $env:LOCALAPPDATA
if (-not $appData -or -not [IO.Path]::IsPathFullyQualified($appData)) { throw "Asked for an absolute LOCALAPPDATA; got '$appData'" }
$file = Join-Path $appData 'porelo-planner\planner.json'
$deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
while (-not (Test-Path -LiteralPath $file) -and [DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Milliseconds 200
}
if (-not (Test-Path -LiteralPath $file)) { throw "Asked for $file within $Seconds s; got none" }

# Read once the file is whole: the planner writes it beside and moves it in.
$address = Get-Content -Raw -LiteralPath $file | ConvertFrom-Json
if ($address.port -isnot [long] -and $address.port -isnot [int]) { throw "Asked for a port in $file; got none" }
if ("$($address.token)" -notmatch '^[A-Za-z0-9_-]{43}$') { throw "Asked for a token in $file; got none of its form" }
$health = Invoke-WebRequest -Uri "http://127.0.0.1:$($address.port)/health" -Headers @{ Authorization = "Bearer $($address.token)" } `
    -TimeoutSec 5 -SkipHttpErrorCheck
if ($health.StatusCode -ne 200) { throw "Asked /health with the token for 200; got $($health.StatusCode)" }

# {status, version, commit}, commit null for a build without a checkout; -AsHashtable keeps a null apart from a
# missing field.
$answer = $health.Content | ConvertFrom-Json -AsHashtable
$expected = "UP $version $commit"
$got = if ($answer -is [hashtable] -and $answer.ContainsKey('status') -and $answer.ContainsKey('version') -and
    $answer.ContainsKey('commit')) {
    "$($answer['status']) $($answer['version']) $(if ($null -eq $answer['commit']) { 'null' } else { $answer['commit'] })"
} else {
    "an answer without status, version and commit: $($health.Content)"
}
if ($got -cne $expected) { throw "Asked /health for $expected; got $got" }
"porelo-planner wrote $file and answers /health with its token: $got"
