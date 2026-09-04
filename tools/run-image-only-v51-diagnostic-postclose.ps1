[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [Parameter(Mandatory = $true)][string]$InputDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
$resolvedInputDirectory = (Resolve-Path -LiteralPath $InputDirectory).Path
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'IMAGE_ONLY v51 post-close evidence must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $resolvedInputDirectory -PathType Container) -or
        ([System.IO.File]::GetAttributes($resolvedInputDirectory) -band
        [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'IMAGE_ONLY v51 post-close input directory is invalid.'
}

@(
    'DASHSCOPE_TOKEN_API_KEY', 'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY', 'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY', 'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_PROFILE_SUCCESSOR_DIAGNOSTIC',
    'RENDERWEAVE_RUN_V51_PROFILE_SUCCESSOR_DIAGNOSTIC'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-v51-diagnostic-postclose-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY v51 post-close summary already exists.'
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_v51_diagnostic_postclose.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v51 post-close verifier tests failed with exit code $LASTEXITCODE."
    }
    & python.exe tools\verify_image_only_v51_diagnostic_postclose.py `
        --repository $repoRoot --input-directory $resolvedInputDirectory --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY v51 post-close verifier did not produce a passing summary.'
    }

    & mvn.cmd -B -ntp -pl renderweave-inference -am `
        '-Dtest=ProfileSuccessorDiagnosticTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v51 post-close inference tests failed with exit code $LASTEXITCODE."
    }

    $env:RENDERWEAVE_RUN_V51_PROFILE_SUCCESSOR_DIAGNOSTIC = 'true'
    $appTests = @(
        'ImageOnlyProfileSuccessorDiagnosticLiveTest',
        'PostgresCertificationStageExecutionStoreTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v51 post-close app tests failed with exit code $LASTEXITCODE."
    }
}
finally {
    $env:RENDERWEAVE_RUN_V51_PROFILE_SUCCESSOR_DIAGNOSTIC = $null
    Pop-Location
}

$summaryRaw = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath
foreach ($forbidden in @(
        'F:\', 'data:image', 'base64', 'providerRequest', 'providerResponse',
        'modelOutput', 'candidateJson', 'rootDocument', 'chain-of-thought')) {
    if ($summaryRaw.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'IMAGE_ONLY v51 post-close summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.authorizationStatus -cne 'CLOSED' -or
        $summary.failureCode -cne 'VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED' -or
        $summary.detailCode -cne
            'VISUAL_GROUNDING_PARENT_CONTAINMENT_ITEM_ZERO_COMPATIBLE' -or
        [int]$summary.openAuthorizationCount -ne 0 -or
        [int]$summary.providerCalls -ne 1 -or
        [int64]$summary.modelTokens -ne 13845 -or
        [int64]$summary.costMicrosCny -ne 228780 -or
        [int]$summary.unsettledReservations -ne 0 -or
        [int64]$summary.goalAggregateModelTokens -ne 121618 -or
        [int64]$summary.goalRemainingModelTokens -ne 1378382 -or
        [bool]$summary.automaticRerunAllowed -or
        [bool]$summary.candidateApplied -or
        [bool]$summary.staticSchemaPublished -or
        [bool]$summary.productionDeployed -or
        -not [bool]$summary.payloadFree) {
    throw 'IMAGE_ONLY v51 post-close summary contract failed.'
}

Write-Host 'IMAGE_ONLY v51 diagnostic post-close gate: PASS'
Write-Host "Summary: $summaryPath"
