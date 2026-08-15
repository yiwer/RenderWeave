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
    throw 'R5P2 paired evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'R5P2 paired evidence directory must already exist.'
}
if (([System.IO.File]::GetAttributes($candidate) -band
        [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'R5P2 paired evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'R5P2 paired evidence directory escapes .sdlc/evidence.'
}

$reportPath = Join-Path $resolvedEvidenceDir 'r5p2-paired-product-view-report.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'r5p2-paired-producer-gate-summary.json'
foreach ($path in @($reportPath, $summaryPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "R5P2 paired evidence output already exists: $(Split-Path -Leaf $path)"
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
            throw "R5P2 payload-safe scan failed for $Name."
        }
    }
}

# Local OCR only. Credential/live selectors are cleared without reading their values.
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
$freezeRevision = 'c206fa5804e9e88104d3175449b9a52fa29c624c'
$producerPaths = @(
    'renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/ProductViewHarness.java',
    'renderweave-inference/src/main/java/cn/hbads/renderweave/inference/live/R5P2PairedProductViewEvaluation.java',
    'renderweave-inference/src/test/java/cn/hbads/renderweave/inference/live/R5P2PairedProductViewEvaluationTest.java',
    'renderweave-app/src/test/java/cn/hbads/renderweave/inference/R5P2PairedProductViewEvaluationGateTest.java',
    'tools/run-r5p2-paired-producer.ps1'
)

$failureStage = 'R5P2_PRODUCER_PRECONDITION'
Push-Location $repoRoot
try {
    try {
        & git merge-base --is-ancestor $freezeRevision HEAD
        if ($LASTEXITCODE -ne 0) {
            throw 'R5P2 freeze revision is not an ancestor of the producer revision.'
        }
        & git diff --quiet -- @producerPaths
        if ($LASTEXITCODE -ne 0) {
            throw 'R5P2 producer source paths must be committed before actual OCR.'
        }
        & git diff --cached --quiet -- @producerPaths
        if ($LASTEXITCODE -ne 0) {
            throw 'R5P2 producer source paths have staged drift before actual OCR.'
        }
        Invoke-Checked 'r5p2-python-authority-process-reconciliation-assignment-tests' {
            Push-Location (Join-Path $repoRoot 'tools')
            try {
                & $pythonExecutable -m unittest `
                    test_verify_r5p2_authority `
                    test_r5p2_public_process `
                    test_r5p2_source_line_reconciliation `
                    test_verify_r5p2_assignment
            }
            finally {
                Pop-Location
            }
        }
        Invoke-Checked 'r5p2-adapter-contract-tests' {
            & $pythonExecutable tools/document-vision/test_rapidocr_adapter.py
        }
        Invoke-Checked 'r5p2-java-precondition-and-payload-guard-tests' {
            & mvn.cmd -B -ntp -pl renderweave-app -am `
                '-Dtest=R5P2AuthorityTest,R5P2SourceLineReconciliationTest,R5P2AssignmentTest,LocalProcessVisualEvidenceAcquisitionTest,R5P2PairedProductViewEvaluationTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }

        $failureStage = 'R5P2_PRODUCER_ACTUAL_EXECUTION'
        $env:RENDERWEAVE_RUN_R5P2_PAIRED_PRODUCER = 'true'
        $env:RENDERWEAVE_R5P2_PAIRED_REPORT = $reportPath
        try {
            Invoke-Checked 'r5p2-paired-two-actual-runs-48-branch-processes' {
                & mvn.cmd -B -ntp -pl renderweave-app -am `
                    '-Dtest=R5P2PairedProductViewEvaluationTest,R5P2PairedProductViewEvaluationGateTest' `
                    '-Dsurefire.failIfNoSpecifiedTests=false' test
            }
        }
        finally {
            [Environment]::SetEnvironmentVariable(
                'RENDERWEAVE_RUN_R5P2_PAIRED_PRODUCER', $null, 'Process')
            [Environment]::SetEnvironmentVariable(
                'RENDERWEAVE_R5P2_PAIRED_REPORT', $null, 'Process')
        }
        if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
            throw 'Actual R5P2 paired producer did not create its canonical report.'
        }

        $failureStage = 'R5P2_PRODUCER_REPORT_VALIDATION'
        $reportRaw = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8
        Assert-PayloadSafe -Name (Split-Path -Leaf $reportPath) -Payload $reportRaw
        $envelope = $reportRaw | ConvertFrom-Json
        $report = $envelope.report
        if ($report.terminalCode -ne 'R5P2_PAIRED_PRODUCER_COMPLETE' -or
                [bool]$report.finalTerminalClaimed -or
                [int]$report.accounting.branchAcquisitionProcesses -ne 48 -or
                [int]$report.accounting.capabilityProbeProcesses -ne 2 -or
                [int]$report.runs.Count -ne 2 -or
                [int]$report.determinism.equivalentCases -ne 12 -or
                [int]$report.determinism.equivalentBranches -ne 24 -or
                -not [bool]$report.determinism.deterministic -or
                [int]$report.externalProviderUsage.attempts -ne 0 -or
                [int]$report.externalProviderUsage.reservations -ne 0 -or
                [int64]$report.externalProviderUsage.costMicrosCny -ne 0 -or
                [int]$report.apiKeyReads -ne 0) {
            throw 'R5P2 paired producer report contract is invalid.'
        }
        $revision = (& git rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
            throw 'Unable to resolve R5P2 paired producer revision.'
        }
        $summary = [ordered]@{
            gateVersion = 'renderweave-r5p2-paired-producer-gate/1.0'
            result = 'PASS'
            assurance = 'A1_TOOL_CAPTURED_COMPLETE_PAIRED_EXECUTION'
            revision = $revision
            authorityIdentity = $report.authorityIdentity
            assignmentIdentity = $report.assignmentIdentity
            fixtureSetIdentity = $report.fixtureSetIdentity
            evaluationIdentity = $report.evaluationIdentity
            thresholdIdentity = $report.thresholdIdentity
            reportIdentity = $envelope.reportIdentity
            stageIdentities = $report.stageIdentities
            accounting = $report.accounting
            determinism = $report.determinism
            diagnostic = [ordered]@{
                summary = $report.diagnosticSummary
                cases = @($report.runs[0].caseResults | Where-Object {
                    $_.cohort -eq 'HISTORICAL_DIAGNOSTIC'
                } | Select-Object caseId, caseIdentity, cohort, partition, pairMetrics)
                transitBoardV3 = $report.transitBoardV3
            }
            confirmation = [ordered]@{
                summary = $report.confirmationSummary
                cases = @($report.runs[0].caseResults | Where-Object {
                    $_.cohort -eq 'SEALED_CONFIRMATION'
                } | Select-Object caseId, caseIdentity, cohort, partition, pairMetrics)
            }
            qualityObservation = [ordered]@{
                producerQualityObservationPass = [bool]$report.producerQualityObservationPass
                finalTerminalClaimed = $false
            }
            holdoutAccess = $report.holdoutAccess
            noNetwork = [ordered]@{
                adapterPythonSocketDeny = $true
                minimalChildEnvironment = $true
            }
            externalProvider = [ordered]@{
                attempts = 0
                reservations = 0
                costMicrosCny = 0
            }
            apiKeyReads = 0
            liveOrJ1Executed = $false
            payload = [ordered]@{
                imagePersistedInEvidence = $false
                ocrOrGoldTextPersisted = $false
                boundingBoxesPersisted = $false
                promptCandidateOrRootDocumentPersisted = $false
            }
            terminalCode = 'R5P2_PAIRED_PRODUCER_COMPLETE'
        }
        $summaryRaw = ($summary | ConvertTo-Json -Depth 40 -Compress) + "`n"
        Assert-PayloadSafe -Name (Split-Path -Leaf $summaryPath) -Payload $summaryRaw
        Write-Utf8NewFile -Path $summaryPath -Content $summaryRaw
    }
    catch {
        if (-not (Test-Path -LiteralPath $summaryPath)) {
            $negative = [ordered]@{
                gateVersion = 'renderweave-r5p2-paired-producer-gate/1.0'
                result = 'FAIL'
                assurance = 'A1_TOOL_CAPTURED_NEGATIVE_TERMINAL'
                firstFailureStage = $failureStage
                failureCode = 'R5P2_PAIRED_PRODUCER_GATE_FAILED'
                externalProvider = [ordered]@{
                    attempts = 0
                    reservations = 0
                    costMicrosCny = 0
                }
                apiKeyReads = 0
                liveOrJ1Executed = $false
                terminalCode = 'R5P2_MEASUREMENT_INVALID'
            }
            Write-Utf8NewFile -Path $summaryPath `
                -Content (($negative | ConvertTo-Json -Depth 10 -Compress) + "`n")
        }
        throw
    }
}
finally {
    @(
        'RENDERWEAVE_RUN_R5P2_PAIRED_PRODUCER',
        'RENDERWEAVE_R5P2_PAIRED_REPORT'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host 'R5P2 paired producer: COMPLETE; branchProcesses=48; probes=2; ProviderAttempts=0; J1=0'
Write-Host "R5P2 paired evidence: $resolvedEvidenceDir"
