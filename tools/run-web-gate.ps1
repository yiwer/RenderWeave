[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$nodeDir = (& powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $PSScriptRoot 'ensure-node24.ps1') | Select-Object -Last 1)
if ($LASTEXITCODE -ne 0 -or -not $nodeDir) {
    throw 'Unable to provision the pinned Node 24 toolchain.'
}

$npmCommand = Join-Path $nodeDir 'npm.cmd'
$previousUserConfig = $env:NPM_CONFIG_USERCONFIG
$env:NPM_CONFIG_USERCONFIG = Join-Path $repoRoot 'web\.npmrc'
Push-Location (Join-Path $repoRoot 'web')
try {
    & $npmCommand ci
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $npmCommand run check
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & $npmCommand run build
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
    if ($null -eq $previousUserConfig) {
        Remove-Item Env:NPM_CONFIG_USERCONFIG -ErrorAction SilentlyContinue
    }
    else {
        $env:NPM_CONFIG_USERCONFIG = $previousUserConfig
    }
}
