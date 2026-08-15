[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$ProducerReport
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$separator = [System.IO.Path]::DirectorySeparatorChar

function Resolve-EvidenceDirectory {
    param([string]$Value)
    $candidate = [System.IO.Path]::GetFullPath(
        $(if ([System.IO.Path]::IsPathRooted($Value)) { $Value }
          else { Join-Path $repoRoot $Value }))
    if (-not $candidate.StartsWith(
            $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $candidate -PathType Container)) {
        throw 'R5P2 A2 evidence directory must already exist below .sdlc/evidence.'
    }
    if (([System.IO.File]::GetAttributes($candidate) -band
            [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'R5P2 A2 evidence directory cannot be a reparse point.'
    }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    if (-not $resolved.StartsWith(
            $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'R5P2 A2 evidence directory escapes .sdlc/evidence.'
    }
    return $resolved
}

function Resolve-ProducerReport {
    param([string]$Value)
    # Metadata admission only. Report content remains unread until A2 is sealed.
    $candidate = [System.IO.Path]::GetFullPath(
        $(if ([System.IO.Path]::IsPathRooted($Value)) { $Value }
          else { Join-Path $repoRoot $Value }))
    if (-not $candidate.StartsWith(
            $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $candidate -PathType Leaf) -or
            (Split-Path -Leaf $candidate) -ne 'r5p2-paired-product-view-report.json') {
        throw 'R5P2 A2 producer report must be the prior canonical report below .sdlc/evidence.'
    }
    if (([System.IO.File]::GetAttributes($candidate) -band
            [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'R5P2 A2 producer report cannot be a reparse point.'
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Assert-PayloadSafe {
    param([string]$Name, [string]$Payload)
    foreach ($forbidden in @(
            '"rawBytes"', '"normalizedBytes"', '"encodedImage"',
            '"boundingBox"', '"sourceBoundingBox"', '"sourcePixelBox"',
            '"ocrText"', '"goldText"', '"promptText"', '"providerRequest"',
            '"providerResponse"', '"modelOutput"', '"candidateJson"',
            '"rootDocument"', '"base64"', 'data:image',
            'ignore prior instructions', 'bearer ')) {
        if ($Payload.IndexOf(
                $forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "R5P2 A2 payload-safe scan failed for $Name."
        }
    }
}

function Invoke-Captured {
    param([string]$Name, [scriptblock]$Action)
    Write-Host "==> $Name"
    $global:LASTEXITCODE = 0
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $Action 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $text = ($lines | Out-String)
    if ($text.Length -gt 0) { Write-Host $text.TrimEnd() }
    Assert-PayloadSafe -Name $Name -Payload $text
    if ($exitCode -ne 0) { throw "$Name failed with exit code $exitCode." }
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

$resolvedEvidenceDir = Resolve-EvidenceDirectory $EvidenceDir
$resolvedProducerReport = Resolve-ProducerReport $ProducerReport
$a2Path = Join-Path $resolvedEvidenceDir 'r5p2-independent-actual-replay.json'
$comparisonPath = Join-Path $resolvedEvidenceDir 'r5p2-a1-a2-comparison.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'r5p2-independent-a2-gate-summary.json'
foreach ($path in @($a2Path, $comparisonPath, $summaryPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "R5P2 A2 evidence output already exists: $(Split-Path -Leaf $path)"
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

# Offline-only execution. Credential/live selectors are cleared without reading values.
@(
    'DASHSCOPE_API_KEY', 'DASHSCOPE_API_KEY_FILE',
    'DASHSCOPE_TOKEN_API_KEY', 'DASHSCOPE_TOKEN_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY', 'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION',
    'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'
$env:RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE = $pythonExecutable
$env:RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT = $adapterScript
$env:RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT = $modelRoot

$producerRevision = 'e4de0742f5c7648fd2a9beaf30cf1d34ebe34bde'
$a2Paths = @(
    'renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/R5P2IndependentReplayProtocol.java',
    'renderweave-inference/src/test/java/cn/hbads/renderweave/inference/live/R5P2IndependentReplayProtocolTest.java',
    'renderweave-inference/src/test/java/cn/hbads/renderweave/inference/live/R5P2IndependentActualReplayGateTest.java',
    'tools/replay_r5p2_paired_a2.py',
    'tools/test_replay_r5p2_paired_a2.py',
    'tools/compare_r5p2_a1_a2.py',
    'tools/test_compare_r5p2_a1_a2.py',
    'tools/run-r5p2-independent-a2.ps1'
)
$producerPaths = @(
    'renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/ProductViewHarness.java',
    'renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/R5P2PairedProductViewEvaluation.java',
    'renderweave-app/src/test/java/cn/hbads/renderweave/inference/R5P2PairedProductViewEvaluationGateTest.java',
    'tools/run-r5p2-paired-producer.ps1'
)

Push-Location $repoRoot
try {
    & git merge-base --is-ancestor $producerRevision HEAD
    if ($LASTEXITCODE -ne 0) {
        throw 'R5P2 producer revision is not an ancestor of the A2 revision.'
    }
    foreach ($path in $a2Paths) {
        & git ls-files --error-unmatch -- $path *> $null
        if ($LASTEXITCODE -ne 0) { throw "R5P2 A2 path is not committed: $path" }
    }
    & git diff --quiet -- @a2Paths
    if ($LASTEXITCODE -ne 0) { throw 'R5P2 A2 source paths have unstaged drift.' }
    & git diff --cached --quiet -- @a2Paths
    if ($LASTEXITCODE -ne 0) { throw 'R5P2 A2 source paths have staged drift.' }
    & git diff --quiet $producerRevision HEAD -- @producerPaths
    if ($LASTEXITCODE -ne 0) {
        throw 'R5P2 producer implementation drifted after the sealed A1 revision.'
    }

    Invoke-Captured 'r5p2-a2-independent-and-comparison-unit-tests' {
        Push-Location (Join-Path $repoRoot 'tools')
        try {
            & $pythonExecutable -m unittest `
                test_replay_r5p2_paired_a2 `
                test_compare_r5p2_a1_a2 `
                test_r5p2_public_process `
                test_r5p2_source_line_reconciliation
        }
        finally { Pop-Location }
    }
    Invoke-Captured 'r5p2-a2-adapter-contract-tests' {
        & $pythonExecutable tools/document-vision/test_rapidocr_adapter.py
    }

    $env:RENDERWEAVE_RUN_R5P2_INDEPENDENT_A2 = 'true'
    $env:RENDERWEAVE_R5P2_A2_EVIDENCE = $a2Path
    try {
        Invoke-Captured 'r5p2-a2-two-runs-48-fresh-public-branch-processes' {
            & mvn.cmd -B -ntp -pl renderweave-inference -am `
                '-Dtest=R5P2IndependentActualReplayGateTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
    }
    finally {
        [Environment]::SetEnvironmentVariable(
            'RENDERWEAVE_RUN_R5P2_INDEPENDENT_A2', $null, 'Process')
        [Environment]::SetEnvironmentVariable(
            'RENDERWEAVE_R5P2_A2_EVIDENCE', $null, 'Process')
    }
    if (-not (Test-Path -LiteralPath $a2Path -PathType Leaf)) {
        throw 'R5P2 independent replay did not seal canonical evidence.'
    }

    $a2Raw = Get-Content -LiteralPath $a2Path -Raw -Encoding UTF8
    Assert-PayloadSafe -Name (Split-Path -Leaf $a2Path) -Payload $a2Raw
    $a2 = $a2Raw | ConvertFrom-Json
    if ([string]$a2.evidence.terminalCode -eq 'R5P2_MEASUREMENT_INVALID') {
        throw "R5P2 independent replay reached negative terminal: $($a2.evidence.firstFailureStage)"
    }
    if ([string]$a2.evidence.terminalCode -ne 'R5P2_INDEPENDENT_REPLAY_COMPLETE' -or
            -not [bool]$a2.evidence.measurementValid) {
        throw 'R5P2 independent replay evidence is not closed.'
    }

    # The producer report is first content-read here, after the independent evidence is sealed.
    Invoke-Captured 'r5p2-post-seal-exact-a1-a2-comparison' {
        & $pythonExecutable tools/compare_r5p2_a1_a2.py `
            --producer $resolvedProducerReport --a2 $a2Path --output $comparisonPath
    }
    $comparisonRaw = Get-Content -LiteralPath $comparisonPath -Raw -Encoding UTF8
    Assert-PayloadSafe -Name (Split-Path -Leaf $comparisonPath) -Payload $comparisonRaw
    $comparison = $comparisonRaw | ConvertFrom-Json
    if ([string]$comparison.comparison.terminalCode -ne 'R5P2_A1_A2_EXACT' -or
            -not [bool]$comparison.comparison.comparisonExact -or
            -not [bool]$comparison.comparison.measurementValid) {
        throw "R5P2 A1/A2 comparison reached negative terminal: $($comparison.comparison.firstMismatchStage)"
    }

    $revision = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
        throw 'Unable to resolve R5P2 A2 revision.'
    }
    $summary = [ordered]@{
        gateVersion = 'renderweave-r5p2-independent-a2-gate/1.0'
        result = 'PASS'
        assurance = 'A2_CROSS_IMPLEMENTATION_PUBLIC_PROCESS_ACTUAL_REPLAY'
        revision = $revision
        producerReportIdentity = $comparison.comparison.producerReportIdentity
        independentEvidenceIdentity = $comparison.comparison.independentEvidenceIdentity
        comparisonIdentity = $comparison.comparisonIdentity
        replayTerminalCode = $a2.evidence.terminalCode
        comparisonTerminalCode = $comparison.comparison.terminalCode
        candidateTerminal = $comparison.comparison.candidateTerminal
        accounting = $a2.evidence.accounting
        determinism = $a2.evidence.determinism
        diagnostic = $a2.evidence.diagnosticSummary
        confirmation = $a2.evidence.confirmationSummary
        transitBoardV3 = $a2.evidence.transitBoardV3
        producerReportReadsDuringIndependentReplay = 0
        producerMetricReadsDuringIndependentReplay = 0
        producerDecisionReadsDuringIndependentReplay = 0
        externalProvider = [ordered]@{ attempts = 0; reservations = 0; costMicrosCny = 0 }
        apiKeyReads = 0
        liveOrJ1Executed = $false
        formalTerminalClaimed = $false
    }
    Write-Utf8NewFile -Path $summaryPath `
        -Content (($summary | ConvertTo-Json -Depth 30 -Compress) + "`n")
    $summaryRaw = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8
    Assert-PayloadSafe -Name (Split-Path -Leaf $summaryPath) -Payload $summaryRaw
}
finally {
    @(
        'RENDERWEAVE_RUN_R5P2_INDEPENDENT_A2', 'RENDERWEAVE_R5P2_A2_EVIDENCE'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host "R5P2 independent A2: exact; candidate=$($comparison.comparison.candidateTerminal); ProviderAttempts=0; J1=0"
Write-Host "R5P2 independent evidence: $resolvedEvidenceDir"
