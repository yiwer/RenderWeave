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
    throw 'IMAGE_ONLY P2 encryption evidence must be below .sdlc/evidence.'
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
$env:RENDERWEAVE_BLOB_ENVELOPE_ENCRYPTION_ENABLED = 'false'

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-p2-encryption-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY P2 encryption summary already exists.'
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_p2_encryption.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 encryption verifier tests failed with exit code $LASTEXITCODE."
    }
    & python.exe tools\verify_image_only_p2_encryption.py `
        --repository $repoRoot --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY P2 encryption verifier did not produce a passing summary.'
    }

    $appTests = @(
        'EnvelopeCryptoTest',
        'FileSystemArtifactKekRingTest',
        'EnvelopeEncryptedBlobStoreTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 encryption tests failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$summaryRaw = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath
foreach ($forbidden in @(
        'API Key', 'private key', 'authorization:', 'data:image', 'base64',
        'filename', 'ocrText', 'modelOutput', 'rootDocument', 'chain-of-thought')) {
    if ($summaryRaw.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'IMAGE_ONLY P2 encryption summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.algorithm -cne 'AES-256-GCM' -or
        [int]$summary.dekBytes -ne 32 -or
        [int]$summary.nonceBytes -ne 12 -or
        [int]$summary.tagBytes -ne 16 -or
        -not [bool]$summary.perArtifactRandomDek -or
        -not [bool]$summary.ciphertextBlobOnly -or
        -not [bool]$summary.wrappedDekPostgresAuthority -or
        -not [bool]$summary.kekSeparateFromPersistentState -or
        -not [bool]$summary.rewrapLeavesPayloadCiphertextUnchanged -or
        -not [bool]$summary.oldKekDestructionRequiresZeroReferences -or
        -not [bool]$summary.missingComponentFailsClosed -or
        -not [bool]$summary.encryptedCrashOrphanReconciled -or
        -not [bool]$summary.plaintextSignatureScanPassed -or
        [int]$summary.unitTestCount -ne 5 -or
        [int]$summary.postgresIntegrationTestCount -ne 9 -or
        [int]$summary.openAuthorizationCount -ne 0 -or
        [int]$summary.verificationProviderUsage.attempts -ne 0 -or
        [int]$summary.verificationProviderUsage.apiKeyReads -ne 0 -or
        [bool]$summary.envelopeEncryptionDefaultEnabled -or
        [bool]$summary.productionConfigured -or
        [bool]$summary.productionLiveAuthorityGranted -or
        [bool]$summary.candidateApplied -or
        [bool]$summary.staticSchemaPublished -or
        [bool]$summary.productionDeployed -or
        -not [bool]$summary.payloadFree) {
    throw 'IMAGE_ONLY P2 encryption summary contract failed.'
}

Write-Host 'IMAGE_ONLY P2 encryption gate: PASS'
Write-Host "Summary: $summaryPath"
