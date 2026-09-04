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
    throw 'IMAGE_ONLY P2 audit/dual-switch evidence must be below .sdlc/evidence.'
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
$env:RENDERWEAVE_PAYLOAD_LIFECYCLE_ENABLED = 'false'

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-p2-audit-dual-switch-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY P2 audit/dual-switch summary already exists.'
}
$exportPath = Join-Path $repoRoot `
    'renderweave-app\target\image-only-p2-audit-export\audit-chain-export.json'
if (Test-Path -LiteralPath $exportPath) {
    Remove-Item -LiteralPath $exportPath -Force
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_p2_audit_dual_switch.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 audit/dual-switch verifier tests failed with exit code $LASTEXITCODE."
    }

    $appTests = @(
        'PostgresLiveAuditChainAndDualSwitchTest',
        'PostgresLiveAuditPayloadScanTest',
        'LiveAdmissionAuditChainTest',
        'PostgresLiveAdmissionStoreTest',
        'PostgresPayloadLifecycleStoreTest',
        'PostgresInferenceRunStoreTest',
        'PostgresCandidateApplyTest',
        'InferenceControllerPolicyTest',
        'PostgresLiveInferenceWorkflowTest',
        'LiveInferenceApiTest',
        'InferenceApiTest',
        'EnvelopeEncryptedBlobStoreTest',
        'ImageOnlyProductionAdmissionTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 audit/dual-switch tests failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $exportPath -PathType Leaf)) {
        throw 'IMAGE_ONLY P2 payload scan test did not export the audit chain.'
    }

    & python.exe tools\verify_image_only_p2_audit_dual_switch.py `
        --repository $repoRoot --export $exportPath --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY P2 audit/dual-switch verifier did not produce a passing summary.'
    }
}
finally {
    Pop-Location
}

$summaryRaw = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath
foreach ($forbidden in @(
        'API Key', 'private key', 'authorization:', 'data:image', 'base64',
        'filename', 'ocrText', 'modelOutput', 'rootDocument', 'chain-of-thought',
        'canary-original-file-name', 'canary-ocr-text', 'canary-full-response',
        'canary-chain-of-thought', 'canary-pii', 'sk-canary', 'canary-image-signature')) {
    if ($summaryRaw.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'IMAGE_ONLY P2 audit/dual-switch summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.chainVerdict -cne 'OK' -or
        -not [bool]$summary.chainReplayIndependent -or
        -not [bool]$summary.runtimeRoleCannotUpdateOrDelete -or
        -not [bool]$summary.atomicCallAuthorization -or
        -not [bool]$summary.crashWithoutAuditCannotDispatch -or
        -not [bool]$summary.dualSwitchDefaultClosed -or
        -not [bool]$summary.switchCombinationsRejectedExceptEleven -or
        -not [bool]$summary.queuedDrainedToStableTerminal -or
        -not [bool]$summary.reopeningDoesNotResurrect -or
        -not [bool]$summary.reviewRequiredUnblockedDuringDrain -or
        $summary.auditIntegrityReasonCode -cne 'AUDIT_INTEGRITY_UNAVAILABLE' -or
        [int]$summary.auditEventCount -lt 2 -or
        [int]$summary.openAuthorizationCount -ne 0 -or
        [int]$summary.verificationProviderUsage.attempts -ne 0 -or
        [int]$summary.verificationProviderUsage.reservations -ne 0 -or
        [int]$summary.verificationProviderUsage.costMicrosCny -ne 0 -or
        [int]$summary.verificationProviderUsage.apiKeyReads -ne 0 -or
        [bool]$summary.productionConfigured -or
        [bool]$summary.productionLiveAuthorityGranted -or
        [bool]$summary.candidateApplied -or
        [bool]$summary.staticSchemaPublished -or
        [bool]$summary.productionDeployed -or
        -not [bool]$summary.payloadFree) {
    throw 'IMAGE_ONLY P2 audit/dual-switch summary contract failed.'
}

Write-Host 'IMAGE_ONLY P2 audit/dual-switch gate: PASS'
Write-Host "Summary: $summaryPath"
