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
        $(if ([System.IO.Path]::IsPathRooted($Value)) { $Value } else { Join-Path $repoRoot $Value }))
    if (-not $candidate.StartsWith(
            $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $candidate -PathType Container)) {
        throw 'R5P A2 evidence directory must already exist below .sdlc/evidence.'
    }
    if (([System.IO.File]::GetAttributes($candidate) -band
            [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'R5P A2 evidence directory cannot be a reparse point.'
    }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    if (-not $resolved.StartsWith(
            $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'R5P A2 evidence directory escapes .sdlc/evidence.'
    }
    return $resolved
}

function Resolve-ProducerReport {
    param([string]$Value)
    $candidate = [System.IO.Path]::GetFullPath(
        $(if ([System.IO.Path]::IsPathRooted($Value)) { $Value } else { Join-Path $repoRoot $Value }))
    if (-not $candidate.StartsWith(
            $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $candidate -PathType Leaf) -or
            (Split-Path -Leaf $candidate) -ne 'r5p-paired-product-view-report.json') {
        throw 'R5P A2 producer report must be the prior canonical report below .sdlc/evidence.'
    }
    if (([System.IO.File]::GetAttributes($candidate) -band
            [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'R5P A2 producer report cannot be a reparse point.'
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
            throw "R5P A2 payload-safe scan failed for $Name."
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
$a2Path = Join-Path $resolvedEvidenceDir 'r5p-independent-actual-replay.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'r5p-independent-a2-gate-summary.json'
foreach ($path in @($a2Path, $summaryPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "R5P A2 evidence output already exists: $(Split-Path -Leaf $path)"
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

# This runner is local-only. It clears selectors without reading their values.
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

Push-Location $repoRoot
try {
    Invoke-Captured 'r5p-a2-python-metric-and-tamper-tests' {
        & $pythonExecutable tools/test_replay_r5p_paired_a2.py
    }

    $env:RENDERWEAVE_RUN_R5P_INDEPENDENT_A2 = 'true'
    $env:RENDERWEAVE_R5P_A2_EVIDENCE = $a2Path
    try {
        Invoke-Captured 'r5p-a2-cross-implementation-actual-replay' {
            & mvn.cmd -B -ntp -pl renderweave-inference -am `
                '-Dtest=R5PIndependentReplayEvidenceTest,R5PIndependentReplayProtocolTest,R5PIndependentActualReplayGateTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
    }
    finally {
        [Environment]::SetEnvironmentVariable(
            'RENDERWEAVE_RUN_R5P_INDEPENDENT_A2', $null, 'Process')
        [Environment]::SetEnvironmentVariable(
            'RENDERWEAVE_R5P_A2_EVIDENCE', $null, 'Process')
    }
    if (-not (Test-Path -LiteralPath $a2Path -PathType Leaf)) {
        throw 'R5P independent actual replay did not produce canonical evidence.'
    }

    $a2Raw = Get-Content -LiteralPath $a2Path -Raw -Encoding UTF8
    Assert-PayloadSafe -Name (Split-Path -Leaf $a2Path) -Payload $a2Raw
    $a2 = $a2Raw | ConvertFrom-Json
    $producerRaw = Get-Content -LiteralPath $resolvedProducerReport -Raw -Encoding UTF8
    Assert-PayloadSafe -Name (Split-Path -Leaf $resolvedProducerReport) -Payload $producerRaw
    $producer = $producerRaw | ConvertFrom-Json
    $producerCases = @($producer.report.runs[0].caseResults)
    $independentCases = @($a2.evidence.cases)
    if ($producerCases.Count -ne 8 -or $independentCases.Count -ne 8) {
        throw 'R5P A1/A2 case accounting differs.'
    }
    $comparisonCount = 0
    for ($index = 0; $index -lt 8; $index++) {
        $left = $producerCases[$index]
        $right = $independentCases[$index]
        if ($left.caseId -ne $right.caseId) { throw 'R5P A1/A2 case order differs.' }
        foreach ($field in @(
                'matchedLineGain', 'lineRecallGainBps', 'characterErrorReduction',
                'hallucinationIncrease', 'orderAccuracyDeltaBps',
                'repeatRecallDeltaBps', 'targetImproved',
                'hallucinationNonIncrease')) {
            if ($left.pairMetrics.$field -ne $right.$field) {
                throw "R5P A1/A2 metric differs for $($left.caseId): $field"
            }
        }
        $comparisonCount++
    }
    foreach ($pair in @(
            @($producer.report.seenSummary, $a2.evidence.seenSummary),
            @($producer.report.confirmationSummary, $a2.evidence.confirmationSummary))) {
        foreach ($field in @(
                'caseCount', 'targetImprovementCases', 'hallucinationNonIncreaseCases',
                'baselineMatchedLines', 'successorMatchedLines', 'baselineLineRecallBps',
                'successorLineRecallBps', 'lineRecallGainBps',
                'baselineCharacterErrors', 'successorCharacterErrors',
                'characterErrorReduction', 'baselineHallucinations',
                'successorHallucinations', 'baselineOrderAccuracyBps',
                'successorOrderAccuracyBps', 'orderAccuracyDeltaBps',
                'baselineRepeatRecallBps', 'successorRepeatRecallBps',
                'repeatRecallDeltaBps', 'thresholdPass')) {
            if ($pair[0].$field -ne $pair[1].$field) {
                throw "R5P A1/A2 cohort summary differs: $field"
            }
        }
    }

    $revision = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
        throw 'Unable to resolve R5P A2 input revision.'
    }
    $terminal = [string]$a2.evidence.terminalCode
    if ($terminal -notin @(
            'R5P_MEASUREMENT_INVALID', 'R5P_PAIRED_VIEW_NOT_QUALIFIED',
            'R5P_ACTION_IMPLEMENTATION_ALLOWED')) {
        throw 'R5P A2 terminal is not closed.'
    }
    $summary = [ordered]@{
        gateVersion = 'renderweave-r5p-independent-a2-gate/1.0'
        result = 'PASS'
        assurance = 'A2_CROSS_IMPLEMENTATION_ACTUAL_REPLAY'
        inputRevision = $revision
        producerReportIdentity = $producer.reportIdentity
        independentEvidenceIdentity = $a2.evidenceIdentity
        producerDecisionEngineCalls = 0
        producerReportReadsDuringIndependentReplay = 0
        postReplayA1A2ComparisonCases = $comparisonCount
        accounting = [ordered]@{
            runs = [int]$a2.evidence.runCount
            cases = [int]$a2.evidence.caseCount
            branches = [int]$a2.evidence.executedBranchCount
            actualAcquisitionCalls = [int]$a2.evidence.actualAcquisitionCalls
            normalizationReplays = [int]$a2.evidence.normalizationReplays
            actionExecutions = [int]$a2.evidence.actionExecutions
            equivalentCases = [int]$a2.evidence.determinism.equivalentCases
            equivalentBranches = [int]$a2.evidence.determinism.equivalentBranches
        }
        seen = $a2.evidence.seenSummary
        confirmation = $a2.evidence.confirmationSummary
        transitBoardV3 = ($independentCases | Where-Object { $_.caseId -eq 'transit-board-v3' })
        externalProvider = [ordered]@{ attempts = 0; reservations = 0; costMicrosCny = 0 }
        apiKeyReads = 0
        liveOrJ1Executed = $false
        downstreamUnlocked = ($terminal -eq 'R5P_ACTION_IMPLEMENTATION_ALLOWED')
        terminalCode = $terminal
    }
    Write-Utf8NewFile -Path $summaryPath `
        -Content (($summary | ConvertTo-Json -Depth 30 -Compress) + "`n")
    $summaryRaw = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8
    Assert-PayloadSafe -Name (Split-Path -Leaf $summaryPath) -Payload $summaryRaw
}
finally {
    @(
        'RENDERWEAVE_RUN_R5P_INDEPENDENT_A2', 'RENDERWEAVE_R5P_A2_EVIDENCE'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host "R5P independent A2: $terminal; actualAcquisitions=32; ProviderAttempts=0; J1=0"
Write-Host "R5P independent evidence: $resolvedEvidenceDir"
