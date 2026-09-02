[CmdletBinding()]
param(
    [string]$EvidenceDir,
    [string]$LocalPostgresBin
)

$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'run-draft-e2e.ps1'
$arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runner,
    '-Journey', 'template-roundtrip')
if ($EvidenceDir) {
    $arguments += @('-EvidenceDir', $EvidenceDir)
}
if ($LocalPostgresBin) {
    $arguments += @('-LocalPostgresBin', $LocalPostgresBin)
}
& powershell.exe @arguments
exit $LASTEXITCODE
