[CmdletBinding()]
param(
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$arguments = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'run-draft-e2e.ps1'),
    '-Journey', 'inference'
)
if ($EvidenceDir) {
    $arguments += @('-EvidenceDir', $EvidenceDir)
}
& powershell.exe @arguments
exit $LASTEXITCODE
