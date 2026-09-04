[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'IMAGE_ONLY P2 confirmation evidence must be below .sdlc/evidence.'
}

@(
    'DASHSCOPE_TOKEN_API_KEY', 'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY', 'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY', 'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_PROFILE_SUCCESSOR_DIAGNOSTIC',
    'RENDERWEAVE_RUN_V52_PROFILE_SUCCESSOR_DIAGNOSTIC'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'
$env:RENDERWEAVE_GATEWAY_ASSERTION_ENABLED = 'false'

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-p2-confirmation-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY P2 confirmation summary already exists.'
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_p2_confirmation.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 confirmation verifier tests failed with exit code $LASTEXITCODE."
    }
    & python.exe tools\verify_image_only_p2_confirmation.py `
        --repository $repoRoot --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY P2 confirmation verifier did not produce a passing summary.'
    }

    $inferenceTests = @(
        'ImageOnlyProductionAdmissionTest',
        'ExternalTransferConfirmationGuardTest',
        'InputNormalizerTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-inference -am `
        "-Dtest=$inferenceTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 confirmation domain tests failed with exit code $LASTEXITCODE."
    }

    & mvn.cmd -B -ntp -pl renderweave-app -am `
        '-Dtest=PostgresLiveAdmissionStoreTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 confirmation PostgreSQL tests failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$summaryRaw = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath
foreach ($forbidden in @(
        'API Key', 'private key', 'compactJws', 'data:image', 'base64',
        'filename', 'ocrText', 'modelOutput', 'rootDocument', 'chain-of-thought')) {
    if ($summaryRaw.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'IMAGE_ONLY P2 confirmation summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        [int]$summary.maximumImages -ne 10 -or
        [int]$summary.maximumImageBytes -ne 10485760 -or
        [int]$summary.maximumImagePixels -ne 25000000 -or
        [int]$summary.maximumBatchBytes -ne 33554432 -or
        [int]$summary.firstDispatchWindowSeconds -ne 900 -or
        [int]$summary.providerCallWindowSeconds -ne 7200 -or
        -not [bool]$summary.runManifestConfirmationAtomic -or
        -not [bool]$summary.immutableFactsAppendOnly -or
        -not [bool]$summary.responseLossReplayReturnsOriginal -or
        -not [bool]$summary.freshGatewayIdentityExcludedFromFingerprint -or
        [bool]$summary.ambiguousAttemptBlindReplayAllowed -or
        [bool]$summary.originalNamePersisted -or
        [int]$summary.openAuthorizationCount -ne 0 -or
        [int]$summary.verificationProviderUsage.attempts -ne 0 -or
        [int]$summary.verificationProviderUsage.apiKeyReads -ne 0 -or
        [bool]$summary.productionConfirmationCreated -or
        [bool]$summary.productionLiveAuthorityGranted -or
        [bool]$summary.candidateApplied -or
        [bool]$summary.staticSchemaPublished -or
        [bool]$summary.productionDeployed -or
        -not [bool]$summary.payloadFree) {
    throw 'IMAGE_ONLY P2 confirmation summary contract failed.'
}

Write-Host 'IMAGE_ONLY P2 confirmation gate: PASS'
Write-Host "Summary: $summaryPath"
