[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
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
    throw 'Renderer tricky-font compatibility evidence must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Renderer tricky-font compatibility evidence directory must already exist.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Renderer tricky-font compatibility evidence directory escapes .sdlc/evidence.'
}

$report = Join-Path $resolvedEvidenceDir 'renderer-tricky-font-compatibility.json'
if (Test-Path -LiteralPath $report) {
    throw "Renderer tricky-font compatibility evidence already exists: $report"
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

$pythonVersion = (& py.exe -3.13 --version 2>&1).Trim()
if ($LASTEXITCODE -ne 0 -or -not $pythonVersion.StartsWith('Python 3.13.')) {
    throw "Python 3.13 is required for Renderer tricky-font compatibility replay; got $pythonVersion."
}

Push-Location $repoRoot
try {
    & py.exe -3.13 'tools\verify-renderer-tricky-font-compatibility.py' `
        '--repo' $repoRoot `
        '--decision' '.scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v2.json' `
        '--report' $report
    if ($LASTEXITCODE -ne 0) {
        throw "Renderer tricky-font compatibility verifier failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$result = Get-Content -Raw -Encoding UTF8 -LiteralPath $report | ConvertFrom-Json
if ($result.reportVersion -ne 'renderweave-renderer-tricky-font-compatibility-gate/1.1' `
        -or $result.status -ne 'PASS_NEW_CANDIDATE_CLASSIFICATION_COMPATIBLE_FAIL_CLOSED' `
        -or $result.decisionStatus -ne 'NEW_CANDIDATE_CLASSIFICATION_COMPILE_PATH_COMPATIBLE_BUILD_UNPROVEN' `
        -or $result.candidateId -ne 'rw-renderer-spike-linux-x86_64-v2-000002' `
        -or $result.predecessorCandidateId -ne 'rw-renderer-spike-linux-x86_64-v2-000001' `
        -or $result.checkCount -lt 100 `
        -or $result.failureCount -ne 0 `
        -or -not $result.observedCompatibility.classificationImplementationCompiled `
        -or -not $result.observedCompatibility.currentCandidateCanSatisfyPortableAuthority `
        -or $result.observedCompatibility.runtimeBytecodeNonExecutionProven `
        -or $result.observedCompatibility.exactBuiltTargetObserved `
        -or $result.boundary.buildAuthorized `
        -or $result.boundary.exactRendererTargetMayMaterialize `
        -or $result.boundary.rendererExactOutputPreissuanceReady `
        -or $result.boundary.rendererExactOutputRecordIssuanceAllowed `
        -or $result.boundary.certified `
        -or $result.boundary.ready `
        -or $result.boundary.ticket19MayClose `
        -or $result.boundary.vendorSourceRetained `
        -or $result.boundary.fontBytesRead -ne 0 `
        -or $result.boundary.networkAttempts -ne 0 `
        -or $result.boundary.providerAttempts -ne 0) {
    throw 'Renderer tricky-font compatibility report boundary drifted.'
}

Write-Host (
    'Renderer tricky-font compatibility: {0}, checks={1}, candidate=000002, target/Certified/READY=false' -f
    $result.status, $result.checkCount
)
