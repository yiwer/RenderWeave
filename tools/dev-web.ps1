[CmdletBinding()]
param(
    [int]$Port = 5173
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$nodeDir = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'ensure-node24.ps1') | Select-Object -Last 1)
if ($LASTEXITCODE -ne 0 -or -not $nodeDir) {
    throw 'Unable to provision the pinned Node 24 toolchain.'
}

$npmCommand = Join-Path $nodeDir 'npm.cmd'
$env:NPM_CONFIG_USERCONFIG = Join-Path $repoRoot 'web\.npmrc'
Push-Location (Join-Path $repoRoot 'web')
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        & $npmCommand ci
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
    & $npmCommand run dev -- --host 127.0.0.1 --port $Port
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}

