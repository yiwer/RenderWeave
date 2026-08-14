[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath,
    [Parameter(Mandatory = $true)]
    [string]$SelectionOutcomePath
)

& (Join-Path $PSScriptRoot 'run-offline-repair-downstream-terminal.ps1') `
    -Ticket VRQ_11_WINNER_HOLDOUT `
    -EvidenceDir $EvidenceDir `
    -DecisionPath $DecisionPath `
    -PredecessorPaths @($SelectionOutcomePath)
if (-not $? -or $LASTEXITCODE -ne 0) {
    throw 'VRQ-11 terminal runner failed.'
}
