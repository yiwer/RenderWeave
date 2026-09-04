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
    throw 'IMAGE_ONLY v52 successor evidence directory must be below .sdlc/evidence.'
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
    'RENDERWEAVE_RUN_PROFILE_SUCCESSOR_DIAGNOSTIC',
    'RENDERWEAVE_RUN_V49_PROFILE_SUCCESSOR_DIAGNOSTIC',
    'RENDERWEAVE_RUN_V50_PROFILE_SUCCESSOR_DIAGNOSTIC',
    'RENDERWEAVE_RUN_V51_PROFILE_SUCCESSOR_DIAGNOSTIC',
    'RENDERWEAVE_RUN_V52_PROFILE_SUCCESSOR_DIAGNOSTIC'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-v52-successor-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY v52 successor summary already exists.'
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_v52_successor.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v52 successor verifier tests failed with exit code $LASTEXITCODE."
    }
    & python.exe tools\verify_image_only_v52_successor.py `
        --repository $repoRoot `
        --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY v52 successor verifier did not produce a passing summary.'
    }

    $inferenceTests = @(
        'ImageOnlyV51ProfileTest',
        'ImageOnlyV52ProfileTest',
        'InferenceProfileRegistryTest',
        'VisualGroundingContractTest',
        'VisualObservationCorrectionPolicyTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-inference -am `
        "-Dtest=$inferenceTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v52 successor inference tests failed with exit code $LASTEXITCODE."
    }

    $appTests = @(
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyFourNormalizesExactItemParentEnvelopeBeforeSecondPermit',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyThreeClassifiesContainmentAndStopsBeforeSecondPermit',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyTwoCanonicalizesOpaqueLocalIdsWithoutARepairCall'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v52 successor PostgreSQL tests failed with exit code $LASTEXITCODE."
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
        throw 'IMAGE_ONLY v52 successor summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.stage -cne 'PROVIDER_ZERO_ITEM_PARENT_ENVELOPE_NORMALIZATION_SUCCESSOR' -or
        $summary.profileId -cne 'dashscope-qwen38-max-product-v52-hybrid-generic' -or
        $summary.profileSha256 -cne 'd8014b605dfa01a5aa1e6062696c61eb896da9e146b2a6ab3c5dae3ca9957332' -or
        $summary.pipelineVersion -cne 'renderweave-inference-pipeline/4.34' -or
        [int]$summary.relativeProfileDiff.Count -ne 2 -or
        [int]$summary.v51ClosedArtifactsReplayed -ne 4 -or
        [int]$summary.maximumNormalizedRepeatedGroupEnvelopes -ne 8 -or
        -not [bool]$summary.directItemChildrenOnly -or
        -not [bool]$summary.noPadding -or
        -not [bool]$summary.atomicRollback -or
        [int]$summary.openAuthorizationCount -ne 0 -or
        -not [bool]$summary.hiddenExperimental -or
        [bool]$summary.productLive -or
        [int]$summary.verificationProviderUsage.attempts -ne 0 -or
        [int]$summary.verificationProviderUsage.reservations -ne 0 -or
        [int64]$summary.verificationProviderUsage.modelTokens -ne 0 -or
        [int64]$summary.verificationProviderUsage.costMicrosCny -ne 0 -or
        [int]$summary.verificationProviderUsage.apiKeyReads -ne 0 -or
        [bool]$summary.candidateApplied -or
        [bool]$summary.staticSchemaPublished -or
        [bool]$summary.productionDeployed -or
        -not [bool]$summary.payloadFree -or
        $summary.implementationIdentity -notmatch '^renderweave-image-only-v52-implementation/1\.0:[0-9a-f]{64}$') {
    throw 'IMAGE_ONLY v52 successor Provider-zero summary contract failed.'
}

Write-Host 'IMAGE_ONLY v52 item-parent envelope successor gate: PASS'
Write-Host "Summary: $summaryPath"
