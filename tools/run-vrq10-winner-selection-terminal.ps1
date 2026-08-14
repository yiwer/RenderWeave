[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath,
    [Parameter(Mandatory = $true)]
    [string]$PpOutcomePath,
    [Parameter(Mandatory = $true)]
    [string]$TesseractOutcomePath
)

& (Join-Path $PSScriptRoot 'run-offline-repair-downstream-terminal.ps1') `
    -Ticket VRQ_10_SOLE_DEV_WINNER_SELECTION `
    -EvidenceDir $EvidenceDir `
    -DecisionPath $DecisionPath `
    -PredecessorPaths @($PpOutcomePath, $TesseractOutcomePath)
if (-not $? -or $LASTEXITCODE -ne 0) {
    throw 'VRQ-10 terminal runner failed.'
}
