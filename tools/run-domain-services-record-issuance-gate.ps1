param(
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $EvidenceDir = Join-Path $repoRoot ".sdlc\evidence\$stamp-domain-services-record-issuance"
}
$resolvedEvidenceDir = [System.IO.Path]::GetFullPath($EvidenceDir)
$null = New-Item -ItemType Directory -Path $resolvedEvidenceDir -Force
$target = '.scratch/renderweave-template-v1/domain-services/capacity-record-issuance-target-v1.json'

Push-Location $repoRoot
try {
    & node.exe '.scratch/renderweave-template-v1/domain-services/validate-domain-services-postissuance-primary.mjs' `
        '--target' $target `
        '--output' (Join-Path $resolvedEvidenceDir 'domain-services-postissuance-primary.json')
    if ($LASTEXITCODE -ne 0) {
        throw "Node post-issuance replay failed with exit code $LASTEXITCODE"
    }
    & python.exe '.scratch/renderweave-template-v1/domain-services/validate_domain_services_postissuance_independent.py' `
        '--target' $target `
        '--output' (Join-Path $resolvedEvidenceDir 'domain-services-postissuance-independent.json')
    if ($LASTEXITCODE -ne 0) {
        throw "Python post-issuance replay failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Host "Evidence: $resolvedEvidenceDir"
