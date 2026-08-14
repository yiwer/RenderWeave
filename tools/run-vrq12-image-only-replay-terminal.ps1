[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath,
    [Parameter(Mandatory = $true)]
    [string]$HoldoutOutcomePath
)

& (Join-Path $PSScriptRoot 'run-offline-repair-downstream-terminal.ps1') `
    -Ticket VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY `
    -EvidenceDir $EvidenceDir `
    -DecisionPath $DecisionPath `
    -PredecessorPaths @($HoldoutOutcomePath)
if (-not $? -or $LASTEXITCODE -ne 0) {
    throw 'VRQ-12 terminal runner failed.'
}
