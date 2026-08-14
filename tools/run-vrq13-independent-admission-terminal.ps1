[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath,
    [Parameter(Mandatory = $true)]
    [string]$ReplayOutcomePath
)

& (Join-Path $PSScriptRoot 'run-offline-repair-downstream-terminal.ps1') `
    -Ticket VRQ_13_INDEPENDENT_A2_ADMISSION `
    -EvidenceDir $EvidenceDir `
    -DecisionPath $DecisionPath `
    -PredecessorPaths @($ReplayOutcomePath)
if (-not $? -or $LASTEXITCODE -ne 0) {
    throw 'VRQ-13 terminal runner failed.'
}
