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
    throw 'IMAGE_ONLY v49 envelope evidence directory must be below .sdlc/evidence.'
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

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-v49-envelope-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY v49 envelope summary already exists.'
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_v49_envelope.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v49 envelope verifier tests failed with exit code $LASTEXITCODE."
    }
    & python.exe tools\verify_image_only_v49_envelope.py `
        --repository $repoRoot `
        --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY v49 envelope verifier did not produce a passing summary.'
    }

    $inferenceTests = @(
        'VisualRegionFallbackClassifierTest',
        'InferenceRejectionEnvelopeTest',
        'VisualGroundingContractTest',
        'VisualObservationCorrectionPolicyTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-inference -am `
        "-Dtest=$inferenceTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v49 envelope inference tests failed with exit code $LASTEXITCODE."
    }

    $appTests = @(
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyOnePersistsMixedEnvelopeBeforeRetryPermit',
        'PostgresLiveInferenceWorkflowTest#postgresRoundTripsUnclassifiedEnvelopeBeforePrimaryOnlyTerminal',
        'PostgresLiveInferenceWorkflowTest#expiredLeaseReplaysPersistedUnclassifiedEnvelopeWithoutAnotherProviderPermit',
        'PostgresLiveInferenceWorkflowTest#postgresRejectsMalformedMixedEnvelopeBeforeItCanEnterTheLedger',
        'InferenceApiTest#executionLogExposesCompleteStructuredProgressWithoutProviderPayloads',
        'InferenceApiTest#executionLogProjectsOnlyTheBoundedMixedRejectionEnvelope'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v49 envelope PostgreSQL/API tests failed with exit code $LASTEXITCODE."
    }

    & npm.cmd --prefix web run api:generate
    if ($LASTEXITCODE -ne 0) { throw 'IMAGE_ONLY v49 envelope API generation failed.' }
    & npm.cmd --prefix web run typecheck
    if ($LASTEXITCODE -ne 0) { throw 'IMAGE_ONLY v49 envelope Web typecheck failed.' }
    & npm.cmd --prefix web run test -- src/features/inference/InferenceMonitorPage.test.tsx
    if ($LASTEXITCODE -ne 0) { throw 'IMAGE_ONLY v49 envelope Web tests failed.' }
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
        throw 'IMAGE_ONLY v49 envelope summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.stage -cne 'PROVIDER_ZERO_ENVELOPE' -or
        [int]$summary.closedDetailCodes.Count -ne 7 -or
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
    throw 'IMAGE_ONLY v49 envelope Provider-zero summary contract failed.'
}

Write-Host 'IMAGE_ONLY v49 rejection envelope gate: PASS'
Write-Host "Summary: $summaryPath"
