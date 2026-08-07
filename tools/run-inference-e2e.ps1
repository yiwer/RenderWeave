[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $PSScriptRoot 'run-draft-e2e.ps1') -Journey inference
exit $LASTEXITCODE
