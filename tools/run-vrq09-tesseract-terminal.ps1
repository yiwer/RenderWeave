[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath
)

& (Join-Path $PSScriptRoot 'run-offline-repair-r2-terminal.ps1') `
    -Ticket VRQ_09_TESSERACT_DEV_BASELINE `
    -EvidenceDir $EvidenceDir `
    -DecisionPath $DecisionPath
if (-not $? -or $LASTEXITCODE -ne 0) {
    throw 'VRQ-09 terminal runner failed.'
}
