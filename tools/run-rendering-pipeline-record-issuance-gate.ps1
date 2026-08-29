[CmdletBinding()]
param(
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $EvidenceDir = Join-Path $evidenceRoot "$stamp-rendering-pipeline-record-issuance"
}
$candidateEvidenceDir = [System.IO.Path]::GetFullPath(
    $(if ([System.IO.Path]::IsPathRooted($EvidenceDir)) {
            $EvidenceDir
        }
        else {
            Join-Path $repoRoot $EvidenceDir
        })
)
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $candidateEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Rendering Pipeline issuance evidence directory must be below .sdlc/evidence.'
}
$null = New-Item -ItemType Directory -Path $candidateEvidenceDir -Force
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
$target = '.scratch/renderweave-template-v1/rendering-pipeline/capacity-record-issuance-target-v1.json'
$primaryOutput = Join-Path $resolvedEvidenceDir 'rendering-pipeline-postissuance-primary.json'
$independentOutput = Join-Path $resolvedEvidenceDir 'rendering-pipeline-postissuance-independent.json'
foreach ($path in @($primaryOutput, $independentOutput)) {
    if (Test-Path -LiteralPath $path) {
        throw "Rendering Pipeline issuance evidence already exists: $path"
    }
}

@(
    'DASHSCOPE_TOKEN_API_KEY',
    'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION',
    'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

Push-Location $repoRoot
try {
    & node '.scratch/renderweave-template-v1/rendering-pipeline/validate-rendering-pipeline-postissuance-primary.mjs' `
        '--target' $target `
        '--output' $primaryOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Node postissuance replay failed with exit code $LASTEXITCODE"
    }
    & py.exe -3.13 `
        '.scratch/renderweave-template-v1/rendering-pipeline/validate_rendering_pipeline_postissuance_independent.py' `
        '--target' $target `
        '--output' $independentOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Python postissuance replay failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$primary = Get-Content -Raw -Encoding UTF8 -LiteralPath $primaryOutput | ConvertFrom-Json
$independent = Get-Content -Raw -Encoding UTF8 -LiteralPath $independentOutput | ConvertFrom-Json
foreach ($report in @($primary, $independent)) {
    if ($report.status -ne 'PASS' -or $report.failureCount -ne 0 `
            -or $report.formalCaseCount -ne 409 -or $report.formalOracleCount -ne 409 `
            -or $report.issuedRenderingPipelineCaseCount -ne 156 `
            -or $report.issuedRenderingPipelineOracleCount -ne 156 `
            -or $report.issuedCapacityCaseCount -ne 363 `
            -or $report.issuedCapacityOracleCount -ne 363 `
            -or $report.boundary.productMutationPerformed `
            -or $report.boundary.externalNetworkAllowed `
            -or $report.boundary.rendererReady `
            -or $report.boundary.ticket19Closed) {
        throw 'Rendering Pipeline postissuance report boundary drifted.'
    }
}
Write-Host (
    'RENDERING_PIPELINE issuance: 409/409 formal, 156+156 appended, checks={0}/{1} PASS' -f
    $primary.checkCount, $independent.checkCount
)
Write-Host "Evidence: $resolvedEvidenceDir"
