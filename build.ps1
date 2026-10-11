# Build script for the SPW remote-control plugin.
#
# Usage:  .\build.ps1
#         .\build.ps1 -Clean
#
# This script sets JAVA_HOME to JDK 25 automatically.
# The Workshop API targets Java 25 (jvmToolchain(25)); JDK 17 or 21 will fail.
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads and writes .ps1
# files using the ANSI code page, so non-ASCII text gets mangled.

[CmdletBinding()]
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

# Script directory. Captured once into a plain variable: when this script is
# invoked as `& .\build.ps1`, $PSScriptRoot can come back empty in scope.
$Here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

# JDK 25 location. The workshop API targets Java 25 (jvmToolchain(25)).
# Honors JAVA_HOME if already set; otherwise looks for a JDK 25 install.
# Override by setting JAVA_HOME before running, e.g.:
#   $env:JAVA_HOME = "C:\path\to\jdk-25"; .\build.ps1
$Jdk = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $env:JAVA_HOME
} else {
    'C:\Users\Administrator\.jdks\jdk-25.0.4.1+1'
}

$javaExe = Join-Path $Jdk 'bin\java.exe'
if (-not (Test-Path $javaExe)) {
    Write-Host "ERROR: JDK 25 not found at: $Jdk" -ForegroundColor Red
    Write-Host "Set JAVA_HOME to a JDK 25 install, or edit the default path in build.ps1" -ForegroundColor Red
    exit 1
}

$env:JAVA_HOME = $Jdk
$env:Path = (Join-Path $Jdk 'bin') + ';' + $env:Path

Write-Host "Using JDK: $Jdk" -ForegroundColor Cyan
# Native stderr output would otherwise be treated as a terminating error
# because $ErrorActionPreference is 'Stop'.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $javaExe -version 2>&1 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
$ErrorActionPreference = $prevEap

Push-Location $Here
try {
    $gradleTasks = @()
    if ($Clean) { $gradleTasks += 'clean' }
    $gradleTasks += 'plugin'

    Write-Host ""
    Write-Host ("Running: gradlew " + ($gradleTasks -join ' ')) -ForegroundColor Cyan
    & .\gradlew.bat @gradleTasks --no-daemon
    if ($LASTEXITCODE -ne 0) {
        Write-Host "BUILD FAILED (exit code $LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }

    $libsDir = Join-Path $Here 'build\libs'
    $spmod = Get-ChildItem (Join-Path $libsDir '*.spmod') -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if (-not $spmod) {
        Write-Host "Build finished but no .spmod artifact was found." -ForegroundColor Yellow
        exit 1
    }

    $kb = [math]::Round($spmod.Length / 1KB, 1)

    # .spmod is just a standard zip container with a different extension.
    # Some import flows only accept .zip, so also emit an identical copy.
    # Both files have the same SHA-256. The zip name derives its version
    # suffix from the spmod file name, so bumping the plugin version updates
    # it automatically
    # (plugin-io.github.motian16.spwremote-1.0.spmod -> spw-remote-1.0.zip).
    # NOTE: keep comments ASCII in this file - it is UTF-8 without BOM and
    # PowerShell 5.1 parses non-ASCII bytes as GBK, which can corrupt lines.
    $spmodVer = $spmod.BaseName -replace '^plugin-.*-(\d[^-]*)$', '$1'
    $zipPath = Join-Path $libsDir ("spw-remote-" + $spmodVer + ".zip")
    # Remove packages left by older versions so build/libs never accumulates
    # stale files, including packages built under a previous plugin id.
    Get-ChildItem -LiteralPath $libsDir -Filter 'spw-remote-*.zip' |
        Where-Object { $_.FullName -ne $zipPath } |
        Remove-Item -Force
    Get-ChildItem -LiteralPath $libsDir -Filter 'plugin-*.spmod' |
        Where-Object { $_.FullName -ne $spmod.FullName } |
        Remove-Item -Force
    Copy-Item -LiteralPath $spmod.FullName -Destination $zipPath -Force

    # Checksums next to the packages: the release notes and the README both
    # ask users to verify what they imported.
    $sums = @()
    foreach ($f in @($spmod.FullName, $zipPath)) {
        $h = (Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash.ToLower()
        $sums += ("{0}  {1}" -f $h, (Split-Path $f -Leaf))
    }
    $sumFile = Join-Path $libsDir 'SHA256SUMS.txt'
    [System.IO.File]::WriteAllText($sumFile, (($sums -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

    Write-Host ""
    Write-Host "BUILD OK" -ForegroundColor Green
    Write-Host "  spmod: $($spmod.FullName)" -ForegroundColor Green
    Write-Host "  zip  : $zipPath" -ForegroundColor Green
    Write-Host "  size : $kb KB" -ForegroundColor Green
    Write-Host "  sha256:" -ForegroundColor Green
    $sums | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Yellow
    Write-Host "  1. Salt Player -> Workshop -> Local import -> pick the .spmod (or the .zip)"
    Write-Host "  2. ENABLE the plugin in Salt Player (importing is not enabling)."
    Write-Host "     A toast will show the connectable addresses and the pairing code."
    Write-Host "  3. cd test-client ; node verify-auth.js <address> <port> <token>"
}
finally {
    Pop-Location
}
