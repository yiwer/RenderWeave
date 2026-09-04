[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'IMAGE_ONLY v49 correction evidence directory must be below .sdlc/evidence.'
}

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

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-v49-correction-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY v49 correction summary already exists.'
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_v49_correction.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v49 correction verifier tests failed with exit code $LASTEXITCODE."
    }
    & python.exe tools\verify_image_only_v49_correction.py `
        --repository $repoRoot `
        --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY v49 correction verifier did not produce a passing summary.'
    }

    $inferenceTests = @(
        'VisualObservationCorrectionPolicyTest',
        'VisualGroundingContractTest',
        'InferenceRejectionEnvelopeTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-inference -am `
        "-Dtest=$inferenceTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v49 correction inference tests failed with exit code $LASTEXITCODE."
    }

    $appTests = @(
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyOnePersistsMixedEnvelopeBeforeRetryPermit',
        'PostgresLiveInferenceWorkflowTest#postgresRoundTripsUnclassifiedEnvelopeBeforePrimaryOnlyTerminal',
        'PostgresLiveInferenceWorkflowTest#expiredLeaseAfterThirdEquivalentMixedSetStopsBeforeAnotherProviderPermit',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyOneCorrectsPromptCoveredMixedFieldsToReviewRequired',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyOneBreaksBeforeTheFourthEquivalentMixedSet',
        'PostgresLiveInferenceWorkflowTest#changingMixedSetsRemainIsolatedButCannotBypassTheTotalCallCap',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointTwentyNineStopsAfterTheThirdEquivalentRejectedAttempt',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyStopsAfterTheThirdAllowlistedEquivalentRejectedAttempt',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyFailsAnUnlistedObserveCodeWithoutAnotherReservation',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyFeedsFieldSpecificEvidenceCodeIntoOneBoundedCorrection',
        'PostgresLiveInferenceWorkflowTest#equivalentRejectCountsAreIsolatedByCodeAndStage'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v49 correction PostgreSQL tests failed with exit code $LASTEXITCODE."
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
        throw 'IMAGE_ONLY v49 correction summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.stage -cne 'PROVIDER_ZERO_CORRECTION' -or
        [int]$summary.promptCoveredDetailCodes.Count -ne 7 -or
        [int]$summary.canonicalBreakerThreshold -ne 3 -or
        [bool]$summary.unclassifiedRetryable -or
        [bool]$summary.v49ProfileCreated -or
        [bool]$summary.v49PromptCreated -or
        [int]$summary.openAuthorizationCount -ne 0 -or
        [int]$summary.verificationProviderUsage.attempts -ne 0 -or
        [int]$summary.verificationProviderUsage.reservations -ne 0 -or
        [int64]$summary.verificationProviderUsage.modelTokens -ne 0 -or
        [int64]$summary.verificationProviderUsage.costMicrosCny -ne 0 -or
        [int]$summary.verificationProviderUsage.apiKeyReads -ne 0 -or
        [bool]$summary.candidateApplied -or
        [bool]$summary.staticSchemaPublished -or
        -not [bool]$summary.payloadFree) {
    throw 'IMAGE_ONLY v49 correction Provider-zero summary contract failed.'
}

Write-Host 'IMAGE_ONLY v49 bounded correction gate: PASS'
Write-Host "Summary: $summaryPath"
