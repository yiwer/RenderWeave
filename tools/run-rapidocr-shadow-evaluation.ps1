[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$candidate = [System.IO.Path]::GetFullPath(
    $(if ([System.IO.Path]::IsPathRooted($EvidenceDir)) { $EvidenceDir } else { Join-Path $repoRoot $EvidenceDir })
)
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $candidate.StartsWith($evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'RapidOCR shadow evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'RapidOCR shadow evidence directory must already exist.'
}
$attributes = [System.IO.File]::GetAttributes($candidate)
if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'RapidOCR shadow evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'RapidOCR shadow evidence directory escapes .sdlc/evidence.'
}

$reportPath = Join-Path $resolvedEvidenceDir 'rapidocr-shadow-report.json'
$verifierPath = Join-Path $resolvedEvidenceDir 'rapidocr-shadow-python-summary.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'rapidocr-shadow-gate-summary.json'
foreach ($path in @($reportPath, $verifierPath, $summaryPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "RapidOCR shadow evidence output already exists: $(Split-Path -Leaf $path)"
    }
}

$pythonExecutable = Join-Path $repoRoot '.sdlc\toolchains\document-vision-venv\Scripts\python.exe'
$adapterScript = Join-Path $repoRoot 'tools\document-vision\rapidocr_adapter.py'
$modelRoot = Join-Path $repoRoot '.sdlc\toolchains\document-vision-venv\Lib\site-packages\rapidocr\models'
$toolchainAvailable = (Test-Path -LiteralPath $pythonExecutable -PathType Leaf) -and
    (Test-Path -LiteralPath $adapterScript -PathType Leaf) -and
    (Test-Path -LiteralPath $modelRoot -PathType Container)
if (-not $toolchainAvailable) {
    throw 'Frozen RapidOCR/OpenVINO toolchain is unavailable.'
}

function Invoke-Checked {
    param([string]$Name, [scriptblock]$Action)
    Write-Host "==> $Name"
    $global:LASTEXITCODE = 0
    & $Action
    if (-not $? -or $LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

function Write-Utf8NewFile {
    param([string]$Path, [string]$Content)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try {
        $writer = New-Object System.IO.StreamWriter($stream, $encoding)
        try { $writer.Write($Content) }
        finally { $writer.Dispose() }
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}

# This gate is local OCR only. Credential and live selectors are cleared without reading values.
@(
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'DASHSCOPE_TOKEN_API_KEY',
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION',
    'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'
$env:RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE = $pythonExecutable
$env:RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT = $adapterScript
$env:RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT = $modelRoot

Push-Location $repoRoot
try {
    Invoke-Checked 'rapidocr-shadow-python-verifier-tests' {
        & python.exe tools/test_verify_rapidocr_shadow_evaluation.py
    }
    Invoke-Checked 'rapidocr-shadow-adapter-contract-tests' {
        & $pythonExecutable tools/document-vision/test_rapidocr_adapter.py
    }

    $env:RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY = 'true'
    try {
        Invoke-Checked 'rapidocr-shadow-runtime-canary' {
            & mvn.cmd -B -ntp -pl renderweave-app -am `
                '-Dtest=DocumentVisionRuntimeCanaryTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
    }
    finally {
        [Environment]::SetEnvironmentVariable('RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY', $null, 'Process')
    }

    $env:RENDERWEAVE_RUN_RAPIDOCR_SHADOW_EVALUATION = 'true'
    $env:RENDERWEAVE_RAPIDOCR_SHADOW_REPORT = $reportPath
    try {
        Invoke-Checked 'rapidocr-shadow-two-actual-runs' {
            & mvn.cmd -B -ntp -pl renderweave-app -am `
                '-Dtest=RapidOcrShadowCaseEvaluatorTest,RapidOcrShadowEvaluationTest,RapidOcrShadowEvaluationGateTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
    }
    finally {
        [Environment]::SetEnvironmentVariable('RENDERWEAVE_RUN_RAPIDOCR_SHADOW_EVALUATION', $null, 'Process')
        [Environment]::SetEnvironmentVariable('RENDERWEAVE_RAPIDOCR_SHADOW_REPORT', $null, 'Process')
    }
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        throw 'Actual RapidOCR evaluation did not produce its canonical report.'
    }
    Invoke-Checked 'rapidocr-shadow-independent-replay' {
        & python.exe tools/verify_rapidocr_shadow_evaluation.py $reportPath `
            --repository $repoRoot --output $verifierPath
    }

    $envelope = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $verified = Get-Content -LiteralPath $verifierPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $revision = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
        throw 'Unable to resolve RapidOCR shadow evidence revision.'
    }
    $summary = [ordered]@{
        reportVersion = 'renderweave-rapidocr-shadow-gate/1.0'
        result = 'PASS'
        assurance = 'A1+A2-strict-input'
        anchorRevision = 'b50d04e710f3a176b5e95336f912460809939d89'
        revision = $revision
        seam = 'normalized ArtifactSet + AcquisitionPolicy -> DocumentObservationIR/1.0'
        authority = [ordered]@{
            corpusV2 = 'shadow-diagnostic-only'
            corpusV1Ac021 = 'unchanged-authoritative'
            certificationEligible = $false
        }
        identities = [ordered]@{
            evaluation = $envelope.report.evaluationIdentity
            report = $envelope.reportIdentity
            corpus = $envelope.report.corpusIdentity
            annotationSet = $envelope.report.annotationSetIdentity
            capability = $envelope.report.evaluationComponents.capabilityIdentity
            acquisitionPolicy = $envelope.report.evaluationComponents.acquisitionPolicyIdentity
            adapter = $envelope.report.evaluationComponents.adapterIdentity
            weight = $envelope.report.evaluationComponents.weightIdentity
        }
        accounting = [ordered]@{
            runs = 2
            casesPerRun = 60
            devPerRun = 45
            holdoutPerRun = 15
            actualAcquisitions = 120
            metricsEquivalentCases = [int]$verified.metricsEquivalentCases
            observationEquivalentCases = [int]$verified.observationEquivalentCases
        }
        metrics = [ordered]@{
            global = $envelope.report.runs[0].global.metricsBps
            partitions = $envelope.report.runs[0].partitions
            domains = $envelope.report.runs[0].domains
            difficulties = $envelope.report.runs[0].difficulties
            diagnosticSlices = $envelope.report.runs[0].diagnosticSlices
            failureSlices = $envelope.report.runs[0].failureSlices
        }
        triggers = $envelope.report.triggers
        evidenceFacts = $envelope.report.evidenceFacts
        externalProvider = [ordered]@{ attempts = 0; reservations = 0; costMicrosCny = 0 }
        payload = [ordered]@{
            textExposure = 'EPHEMERAL_UNTRUSTED'
            imagePersisted = $false
            contentHashPersisted = $false
            promptPersisted = $false
            providerPayloadPersisted = $false
        }
        independentVerifier = [ordered]@{
            result = $verified.result
            assurance = $verified.assurance
            aggregateIdentity = $verified.aggregateIdentity
        }
    }
    Write-Utf8NewFile -Path $summaryPath -Content (($summary | ConvertTo-Json -Depth 30 -Compress) + "`n")

    foreach ($path in @($reportPath, $verifierPath, $summaryPath)) {
        $payload = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        foreach ($forbidden in @(
                '"ocrText"', '"ocr_text"', '"imageBytes"', '"promptText"',
                '"providerRequest"', '"providerResponse"', '"modelOutput"',
                '"candidateJson"', '"boundingBox"', '"rootDocument"', '"base64"',
                'data:image', 'ignore prior instructions', 'bearer ')) {
            if ($payload.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw "Payload-safe scan failed for $(Split-Path -Leaf $path)."
            }
        }
    }
}
finally {
    @(
        'RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY',
        'RENDERWEAVE_RUN_RAPIDOCR_SHADOW_EVALUATION',
        'RENDERWEAVE_RAPIDOCR_SHADOW_REPORT'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host "RapidOCR shadow result: PASS; actualAcquisitions=120; ProviderAttempts=0"
Write-Host "RapidOCR shadow evidence: $resolvedEvidenceDir"
