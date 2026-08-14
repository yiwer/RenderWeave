[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath,
    [Parameter(Mandatory = $true)]
    [string]$AdmissionOutcomePath
)

& (Join-Path $PSScriptRoot 'run-offline-repair-downstream-terminal.ps1') `
    -Ticket VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY `
    -EvidenceDir $EvidenceDir `
    -DecisionPath $DecisionPath `
    -PredecessorPaths @($AdmissionOutcomePath)
if (-not $? -or $LASTEXITCODE -ne 0) {
    throw 'VRQ-14 terminal runner failed.'
}
