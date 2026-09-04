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
    throw 'IMAGE_ONLY v52 diagnostic preparation evidence must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $resolvedInputDirectory -PathType Container) -or
        ([System.IO.File]::GetAttributes($resolvedInputDirectory) -band
        [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'IMAGE_ONLY v52 diagnostic preparation input directory is invalid.'
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

$summaryPath = Join-Path $resolvedEvidenceDir `
    'image-only-v52-diagnostic-preparation-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY v52 diagnostic preparation summary already exists.'
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_v52_diagnostic_preparation.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v52 diagnostic verifier tests failed with exit code $LASTEXITCODE."
    }
    & python.exe tools\verify_image_only_v52_diagnostic_preparation.py `
        --repository $repoRoot `
        --input-directory $resolvedInputDirectory `
        --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY v52 diagnostic verifier did not produce a passing summary.'
    }

    $inferenceTests = @(
        'InferenceProfileRegistryTest',
        'ImageOnlyV52ProfileTest',
        'VisualGroundingContractTest',
        'VisualObservationCorrectionPolicyTest',
        'ImageOnlyCertificationAuthorizationTest',
        'ProfileSuccessorDiagnosticTest',
        'ProfileCertificationServiceTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-inference -am `
        "-Dtest=$inferenceTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v52 diagnostic inference tests failed with exit code $LASTEXITCODE."
    }

    $appTests = @(
        'PostgresCertificationStageExecutionStoreTest',
        'PostgresLiveInferenceWorkflowTest#pipelineFourPointThirtyFourNormalizesExactItemParentEnvelopeBeforeSecondPermit'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY v52 diagnostic PostgreSQL tests failed with exit code $LASTEXITCODE."
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
        throw 'IMAGE_ONLY v52 diagnostic preparation summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.stage -cne 'PROFILE_SUCCESSOR_DIAGNOSTIC_1_PREPARATION' -or
        [bool]$summary.scoring -or
        $summary.authorizationStatus -cne 'PENDING_J1' -or
        $summary.profileId -cne 'dashscope-qwen38-max-product-v52-hybrid-generic' -or
        $summary.profileSha256 -cne 'd8014b605dfa01a5aa1e6062696c61eb896da9e146b2a6ab3c5dae3ca9957332' -or
        $summary.normalizationIdentity -cne
            'renderweave-image-only-fresh-normalization/1.0:e0e505c515ff3c7c7bac57e0ddc19e714721e301fd2216830bc6ac82f98cae35' -or
        $summary.itemParentEnvelopeTelemetryCode -cne
            'VISUAL_GROUNDING_REPEATED_GROUP_ENVELOPE_NORMALIZED' -or
        $summary.successorImplementationIdentity -cne
            'renderweave-image-only-v52-implementation/1.0:293fec9792df98131d72acffdc22ed4b4d65e0d8edaea1b46743e9e7da2b7405' -or
        [int]$summary.maximumRuns -ne 1 -or
        [int]$summary.maximumProviderCalls -ne 5 -or
        [int64]$summary.maximumModelTokens -ne 100000 -or
        [int64]$summary.maximumCostMicrosCny -ne 3000000 -or
        [int64]$summary.goalAggregateModelTokensBefore -ne 121618 -or
        [int64]$summary.goalWorstCaseModelTokensAfter -ne 221618 -or
        [int64]$summary.goalModelTokenCap -ne 1500000 -or
        [int]$summary.openAuthorizationCount -ne 0 -or
        [int]$summary.verificationProviderUsage.attempts -ne 0 -or
        [int]$summary.verificationProviderUsage.reservations -ne 0 -or
        [int64]$summary.verificationProviderUsage.modelTokens -ne 0 -or
        [int64]$summary.verificationProviderUsage.costMicrosCny -ne 0 -or
        [int]$summary.verificationProviderUsage.apiKeyReads -ne 0 -or
        [bool]$summary.candidateApplied -or
        [bool]$summary.staticSchemaPublished -or
        [bool]$summary.productionDeploymentAllowed -or
        -not [bool]$summary.payloadFree -or
        $summary.authorityImplementationIdentity -notmatch
            '^renderweave-image-only-v52-diagnostic-authority/1\.0:[0-9a-f]{64}$') {
    throw 'IMAGE_ONLY v52 diagnostic Provider-zero summary contract failed.'
}

Write-Host 'IMAGE_ONLY v52 diagnostic preparation gate: PASS'
Write-Host "Summary: $summaryPath"
