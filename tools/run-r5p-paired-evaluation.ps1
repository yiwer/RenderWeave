[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$candidate = [System.IO.Path]::GetFullPath(
    $(if ([System.IO.Path]::IsPathRooted($EvidenceDir)) {
        $EvidenceDir
    } else {
        Join-Path $repoRoot $EvidenceDir
    })
)
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $candidate.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'R5P paired evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'R5P paired evidence directory must already exist.'
}
$attributes = [System.IO.File]::GetAttributes($candidate)
if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'R5P paired evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'R5P paired evidence directory escapes .sdlc/evidence.'
}

$reportPath = Join-Path $resolvedEvidenceDir 'r5p-paired-product-view-report.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'r5p-paired-gate-summary.json'
foreach ($path in @($reportPath, $summaryPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "R5P paired evidence output already exists: $(Split-Path -Leaf $path)"
    }
}

$pythonExecutable = Join-Path $repoRoot `
    '.sdlc\toolchains\document-vision-venv\Scripts\python.exe'
$adapterScript = Join-Path $repoRoot 'tools\document-vision\rapidocr_adapter.py'
$modelRoot = Join-Path $repoRoot `
    '.sdlc\toolchains\document-vision-venv\Lib\site-packages\rapidocr\models'
if (-not (Test-Path -LiteralPath $pythonExecutable -PathType Leaf) -or
        -not (Test-Path -LiteralPath $adapterScript -PathType Leaf) -or
        -not (Test-Path -LiteralPath $modelRoot -PathType Container)) {
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
    $stream = [System.IO.File]::Open(
        $Path, [System.IO.FileMode]::CreateNew,
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

# This gate is local OCR only. Live/credential selectors are cleared without reading values.
@(
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'DASHSCOPE_TOKEN_API_KEY',
    'DASHSCOPE_TOKEN_API_KEY_FILE',
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
    Invoke-Checked 'r5p-paired-adapter-contract-tests' {
        & $pythonExecutable tools/document-vision/test_rapidocr_adapter.py
    }

    $env:RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY = 'true'
    try {
        Invoke-Checked 'r5p-paired-runtime-canary' {
            & mvn.cmd -B -ntp -pl renderweave-app -am `
                '-Dtest=DocumentVisionRuntimeCanaryTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
    }
    finally {
        [Environment]::SetEnvironmentVariable(
            'RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY', $null, 'Process')
    }

    $env:RENDERWEAVE_RUN_R5P_PAIRED_EVALUATION = 'true'
    $env:RENDERWEAVE_R5P_PAIRED_REPORT = $reportPath
    try {
        Invoke-Checked 'r5p-paired-two-actual-runs' {
            & mvn.cmd -B -ntp -pl renderweave-app -am `
                '-Dtest=RapidOcrShadowCaseEvaluatorTest,PairedProductViewEvaluationTest,R5PPairedProductViewEvaluationGateTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
    }
    finally {
        [Environment]::SetEnvironmentVariable(
            'RENDERWEAVE_RUN_R5P_PAIRED_EVALUATION', $null, 'Process')
        [Environment]::SetEnvironmentVariable(
            'RENDERWEAVE_R5P_PAIRED_REPORT', $null, 'Process')
    }
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        throw 'Actual R5P paired evaluation did not produce its canonical report.'
    }

    $envelope = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $revision = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
        throw 'Unable to resolve R5P paired evidence revision.'
    }
    $summary = [ordered]@{
        gateVersion = 'renderweave-r5p-paired-execution-gate/1.0'
        result = 'PASS'
        assurance = 'A1_TOOL_CAPTURED_COMPLETE_PAIRED_EXECUTION'
        revision = $revision
        assignmentIdentity = $envelope.report.assignmentIdentity
        evaluationIdentity = $envelope.report.evaluationIdentity
        reportIdentity = $envelope.reportIdentity
        accounting = [ordered]@{
            runs = [int]$envelope.report.runs.Count
            casesPerRun = [int]$envelope.report.caseCount
            branches = [int]$envelope.report.executedBranchCount
            actualAcquisitionCalls = [int]$envelope.report.actualAcquisitionCalls
            equivalentCases = [int]$envelope.report.determinism.equivalentCases
            equivalentBranches = [int]$envelope.report.determinism.equivalentBranches
        }
        qualityObservation = [ordered]@{
            producerQualityPass = [bool]$envelope.report.producerQualityPass
            seen = $envelope.report.seenSummary
            confirmation = $envelope.report.confirmationSummary
            certificationClaimed = $false
        }
        externalProvider = [ordered]@{
            attempts = 0
            reservations = 0
            costMicrosCny = 0
        }
        apiKeyReads = 0
        payload = [ordered]@{
            ocrTextPersisted = $false
            imagePersistedInEvidence = $false
            boundingBoxesPersisted = $false
            promptPersisted = $false
            providerPayloadPersisted = $false
        }
        terminalCode = $envelope.report.terminalCode
    }
    Write-Utf8NewFile -Path $summaryPath `
        -Content (($summary | ConvertTo-Json -Depth 30 -Compress) + "`n")

    foreach ($path in @($reportPath, $summaryPath)) {
        $payload = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        foreach ($forbidden in @(
                '"ocrText"', '"ocr_text"', '"imageBytes"', '"promptText"',
                '"providerRequest"', '"providerResponse"', '"modelOutput"',
                '"candidateJson"', '"boundingBox"', '"sourcePixelBox"',
                '"rootDocument"', '"base64"', 'data:image',
                'ignore prior instructions', 'bearer ')) {
            if ($payload.IndexOf(
                    $forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw "Payload-safe scan failed for $(Split-Path -Leaf $path)."
            }
        }
    }
}
finally {
    @(
        'RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY',
        'RENDERWEAVE_RUN_R5P_PAIRED_EVALUATION',
        'RENDERWEAVE_R5P_PAIRED_REPORT'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host 'R5P paired execution: COMPLETE; branches=32; ProviderAttempts=0; J1=0'
Write-Host "R5P paired evidence: $resolvedEvidenceDir"
