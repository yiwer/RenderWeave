[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,

    [Parameter(Mandatory = $true)]
    [string]$InputDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'IMAGE_ONLY v47 successor evidence directory must be below .sdlc/evidence.'
}
$resolvedInputDirectory = (Resolve-Path -LiteralPath $InputDirectory).Path
if (-not (Test-Path -LiteralPath $resolvedInputDirectory -PathType Container) -or
        ([System.IO.File]::GetAttributes($resolvedInputDirectory) -band
        [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'IMAGE_ONLY v47 successor input directory is invalid.'
}

# Provider-zero contract: clear selector names without reading or displaying prior values.
@(
    'DASHSCOPE_TOKEN_API_KEY',
    'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_IMAGE_ONLY_CERTIFICATION_CANARY',
    'RENDERWEAVE_RUN_PROFILE_SUCCESSOR_DIAGNOSTIC'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-v47-successor-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY v47 successor summary already exists.'
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_v47_successor.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v47 independent verifier tests failed with exit code $LASTEXITCODE."
    }
    & python.exe tools\verify_image_only_v47_successor.py `
        --repository $repoRoot `
        --input-directory $resolvedInputDirectory `
        --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY v47 independent verification did not produce a passing summary.'
    }

    $inferenceTests = @(
        'InferenceProfileRegistryTest',
        'InferencePromptRegistryTest',
        'ImageOnlyV46ProfileTest',
        'ImageOnlyV47ProfileTest',
        'ImageOnlyCertificationAuthorizationTest',
        'ProfileSuccessorDiagnosticTest',
        'ProfileCertificationServiceTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-inference -am `
        "-Dtest=$inferenceTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v47 inference tests failed with exit code $LASTEXITCODE."
    }

    $appTests = 'PostgresCertificationStageExecutionStoreTest,' +
        'PostgresLiveInferenceWorkflowTest#' +
        'pipelineFourPointTwentyNineStopsAfterTheThirdEquivalentRejectedAttempt+' +
        'equivalentRejectCountsAreIsolatedByCodeAndStage+' +
        'historicalPipelineFourPointTwentyEightKeepsItsTwelveCallSnapshot+' +
        'groundedLengthStopPersistsTruncationWithoutParsingPartialPayload'
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v47 PostgreSQL tests failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$summaryRaw = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath
foreach ($forbidden in @(
        'F:\', 'data:image', 'base64', 'providerRequest', 'providerResponse',
        'modelOutput', 'candidateJson', 'rootDocument', 'chain-of-thought')) {
    if ($summaryRaw.IndexOf(
            $forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'IMAGE_ONLY v47 successor summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.authorizationStatus -cne 'PENDING_J1' -or
        [int]$summary.externalProviderUsage.attempts -ne 0 -or
        [int]$summary.externalProviderUsage.reservations -ne 0 -or
        [int64]$summary.externalProviderUsage.costMicrosCny -ne 0 -or
        [int]$summary.externalProviderUsage.apiKeyReads -ne 0) {
    throw 'IMAGE_ONLY v47 successor Provider-zero summary contract failed.'
}

Write-Host 'IMAGE_ONLY v47 successor Provider-zero gate: PASS'
Write-Host "Summary: $summaryPath"
