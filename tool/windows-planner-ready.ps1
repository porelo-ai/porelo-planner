# The planner's own part of the MSIX install check: run by the window's install check once it has started
# porelo-planner.exe through the PATH, it waits for the planner's planner.json in the account's real
# %LOCALAPPDATA%\porelo-planner, where the window reads it, has the planner prove the token the file holds and asks
# /health signed under it, as the window does, holding the answer to its proof. It ends
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
$port = [int] $address.port
$hmac = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes("$($address.token)"))
function Get-Base64Url([byte[]] $Bytes) { [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_') }
# The label and a zero byte, then each field as its length in four bytes, big endian, and its bytes.
function Get-Mac([string] $Label, [byte[][]] $Fields) {
    $form = [IO.MemoryStream]::new()
    $labelBytes = [Text.Encoding]::ASCII.GetBytes($Label)
    $form.Write($labelBytes, 0, $labelBytes.Length)
    $form.WriteByte(0)
    foreach ($field in $Fields) {
        $length = [BitConverter]::GetBytes([int] $field.Length)
        if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($length) }
        $form.Write($length, 0, 4)
        $form.Write($field, 0, $field.Length)
    }
    Get-Base64Url $hmac.ComputeHash($form.ToArray())
}
$sha = [Security.Cryptography.SHA256]

$challenge = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$proof = Invoke-WebRequest -Uri "http://127.0.0.1:$port/proof" -Method Post -ContentType 'application/json' `
    -Body "{`"challenge`":`"$(Get-Base64Url $challenge)`"}" -TimeoutSec 10 -SkipHttpErrorCheck
[byte[]] $proven = [Text.Encoding]::ASCII.GetBytes('porelo-planner-proof-1') + [byte] 0 + [byte] ($port -shr 8) +
    [byte] ($port -band 255) + $challenge
if ($proof.StatusCode -ne 200 -or $proof.Content -cne "{`"proof`":`"$(Get-Base64Url $hmac.ComputeHash($proven))`"}") {
    throw "Asked the listener on port $port for its proof of the token; got HTTP $($proof.StatusCode) without it"
}

$nonce = [Security.Cryptography.RandomNumberGenerator]::GetBytes(16)
$time = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$ascii = [Text.Encoding]::ASCII
$signature = Get-Mac 'porelo-planner-request-1' @(
    $ascii.GetBytes('GET'), $ascii.GetBytes('/health'), $ascii.GetBytes("127.0.0.1:$port"), [byte[]] @(),
    $sha::HashData([byte[]] @()), $ascii.GetBytes("$time"), $nonce)
$health = Invoke-WebRequest -Uri "http://127.0.0.1:$port/health" -TimeoutSec 5 -SkipHttpErrorCheck `
    -Headers @{ 'Porelo-Signature' = "porelo-planner-request-1;t=$time;n=$(Get-Base64Url $nonce);s=$signature" }
if ($health.StatusCode -ne 200) { throw "Asked /health signed under the token for 200; got $($health.StatusCode)" }
$body = $health.RawContentStream.ToArray()
$expectedProof = 'porelo-planner-answer-1;s=' + (Get-Mac 'porelo-planner-answer-1' @(
        $nonce, $ascii.GetBytes('200'), $sha::HashData($body)))
$gotProof = "$($health.Headers['Porelo-Answer'])"
if ($gotProof -cne $expectedProof) { throw "Asked /health for an answer proven under the token; got '$gotProof'" }

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
"porelo-planner wrote $file, proves its token and answers /health signed under it: $got"
