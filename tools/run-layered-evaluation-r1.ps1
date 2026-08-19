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
    throw 'Layered R1 evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'Layered R1 evidence directory must already exist.'
}
$attributes = [System.IO.File]::GetAttributes($candidate)
if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Layered R1 evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Layered R1 evidence directory escapes .sdlc/evidence.'
}

$reportPath = Join-Path $resolvedEvidenceDir 'layered-report.json'
$pythonSummaryPath = Join-Path $resolvedEvidenceDir 'python-verifier-summary.json'
$gateSummaryPath = Join-Path $resolvedEvidenceDir 'layered-r1-summary.json'
$independentSummaryPath = Join-Path $resolvedEvidenceDir 'layered-r1-independent-summary.json'
$inferenceJUnitPath = Join-Path $resolvedEvidenceDir 'r1-junit-inference.xml'
$appJUnitPath = Join-Path $resolvedEvidenceDir 'r1-junit-app.xml'
$r0ReportPath = Join-Path $resolvedEvidenceDir 'document-observation-r0-summary.json'
$r0InferenceJUnitPath = Join-Path $resolvedEvidenceDir 'r0-junit-inference.xml'
$r0AppJUnitPath = Join-Path $resolvedEvidenceDir 'r0-junit-app.xml'
$r0Inputs = @($r0ReportPath, $r0InferenceJUnitPath, $r0AppJUnitPath)
foreach ($path in $r0Inputs) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Layered R1 requires same-directory R0 evidence: $(Split-Path -Leaf $path)"
    }
}
$outputs = @(
    $reportPath, $pythonSummaryPath, $gateSummaryPath, $independentSummaryPath,
    $inferenceJUnitPath, $appJUnitPath
)
foreach ($path in $outputs) {
    if (Test-Path -LiteralPath $path) {
        throw "Layered R1 evidence output already exists: $(Split-Path -Leaf $path)"
    }
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
        try {
            $writer.Write($Content)
        }
        finally {
            $writer.Dispose()
        }
    }
    finally {
        if ($null -ne $stream) { $stream.Dispose() }
    }
}

function Merge-JUnitReports {
    param([string[]]$Sources, [string]$Destination)
    $content = New-Object System.Text.StringBuilder
    $null = $content.Append('<testsuites>')
    foreach ($source in $Sources) {
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Required JUnit report is missing: $source"
        }
        [xml]$document = Get-Content -LiteralPath $source -Raw -Encoding UTF8
        if ($document.DocumentElement.LocalName -ne 'testsuite') {
            throw "Required JUnit report has an unexpected root: $source"
        }
        $suite = $document.DocumentElement
        $failures = [int]$suite.GetAttribute('failures')
        $errors = [int]$suite.GetAttribute('errors')
        $skipped = [int]$suite.GetAttribute('skipped')
        if ($failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
            throw "Required JUnit report is not fully green: $source"
        }
        $null = $content.Append($suite.OuterXml)
    }
    $null = $content.Append('</testsuites>')
    Write-Utf8NewFile -Path $Destination -Content $content.ToString()
}

function Get-SurefireReport {
    param([string]$Module, [string]$ClassName)
    $directory = Join-Path $repoRoot "$Module\target\surefire-reports"
    $match = Get-ChildItem -LiteralPath $directory -Filter "TEST-*.$ClassName.xml" -File |
        Select-Object -First 1
    if ($null -eq $match) {
        throw "Missing Surefire report for $Module/$ClassName."
    }
    return $match.FullName
}

# R0/R1 must remain zero-external-Provider even when invoked outside run-gate.ps1.
# Values are cleared without being read or displayed.
@(
    'DASHSCOPE_TOKEN_API_KEY',
    'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION',
    'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

$inferenceTestClasses = @(
    'LayeredEvaluationContractsTest',
    'LayeredMetricGoldenTest',
    'LayeredVisualCorpusTest',
    'LayeredVisualEvaluatorTest',
    'LayeredEvaluationReporterTest',
    'LayeredLocalVisualDiffTest',
    'LayeredR1EvaluationGateTest',
    'DocumentObservationSuccessorIdentityTest'
)
$appTestClasses = @('LocalProcessVisualEvidenceAcquisitionTest')

Push-Location $repoRoot
try {
    Invoke-Checked 'r1-r0-same-revision-prerequisite' {
        & python.exe tools/verify_document_observation_r0.py `
            --report $r0ReportPath --repository $repoRoot
    }
    Invoke-Checked 'r1-python-independent-metric-tests' {
        & python.exe tools/test_verify_layered_evaluation.py
    }
    Invoke-Checked 'r1-independent-gate-and-payload-tests' {
        & python.exe tools/test_verify_layered_evaluation_gate.py
    }

    $env:RENDERWEAVE_RUN_LAYERED_R1_EVALUATION = 'true'
    $env:RENDERWEAVE_LAYERED_R1_REPORT = $reportPath
    try {
        $testSelection = @($inferenceTestClasses + $appTestClasses) -join ','
        Invoke-Checked 'r1-java-layered-evaluation-and-adapter-contracts' {
            & mvn.cmd -B -ntp -pl renderweave-app -am `
                "-Dtest=$testSelection" `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
    }
    finally {
        [Environment]::SetEnvironmentVariable('RENDERWEAVE_RUN_LAYERED_R1_EVALUATION', $null, 'Process')
        [Environment]::SetEnvironmentVariable('RENDERWEAVE_LAYERED_R1_REPORT', $null, 'Process')
    }

    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        throw 'Java layered evaluation completed without producing layered-report.json.'
    }
    $inferenceReports = @($inferenceTestClasses | ForEach-Object {
        Get-SurefireReport -Module 'renderweave-inference' -ClassName $_
    })
    $appReports = @($appTestClasses | ForEach-Object {
        Get-SurefireReport -Module 'renderweave-app' -ClassName $_
    })
    Merge-JUnitReports -Sources $inferenceReports -Destination $inferenceJUnitPath
    Merge-JUnitReports -Sources $appReports -Destination $appJUnitPath

    Invoke-Checked 'r1-python-independent-report-replay' {
        & python.exe tools/verify_layered_evaluation.py $reportPath `
            --repository $repoRoot --output $pythonSummaryPath
    }
    if (-not (Test-Path -LiteralPath $pythonSummaryPath -PathType Leaf)) {
        throw 'Python layered verifier completed without producing its summary.'
    }

    $pythonSummary = Get-Content -LiteralPath $pythonSummaryPath -Raw -Encoding UTF8 |
        ConvertFrom-Json
    $r0Summary = Get-Content -LiteralPath $r0ReportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $protectedPaths = @(
        'renderweave-inference/src/main/resources/inference-profiles/dashscope-qwen37-flash-product-v45-hybrid-generic.json',
        'renderweave-inference/src/main/resources/inference-profiles/dashscope-qwen37-plus-product-v45-hybrid-generic.json',
        'renderweave-inference/src/main/resources/inference-profiles/dashscope-qwen38-max-product-v45-hybrid-generic.json',
        'renderweave-inference/src/main/resources/inference-prompts/document-vision-observations-v1.txt',
        'renderweave-inference/src/main/resources/inference-prompts/schema-candidate-v5.txt',
        'renderweave-inference/src/main/resources/inference-prompts/visual-bindings-v4.txt',
        'renderweave-inference/src/main/resources/inference-prompts/visual-elements-v12.txt',
        'renderweave-inference/src/main/resources/inference-prompts/visual-hierarchy-v7.txt',
        'renderweave-inference/src/main/resources/inference-prompts/visual-hint-generic-v1.txt',
        'renderweave-inference/src/main/resources/replay-corpus/v1/manifest.json',
        'renderweave-inference/src/main/resources/visual-eval/v1/FONT-NOTICE.md',
        'renderweave-inference/src/main/resources/visual-eval/v1/OFL.txt',
        'renderweave-inference/src/main/resources/visual-eval/v1/RenderWeaveVisualEval.ttf',
        'renderweave-inference/src/main/resources/visual-eval/v1/scenes.json'
    )
    $protectedFiles = @($protectedPaths | ForEach-Object {
        $absolute = Join-Path $repoRoot $_
        if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) {
            throw "Protected v45 file is missing: $_"
        }
        [ordered]@{
            path = $_
            sha256 = (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
    $revision = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[0-9a-f]{40}$') {
        throw 'Unable to resolve the layered R1 evidence revision.'
    }
    $evidenceFiles = @(
        'document-observation-r0-summary.json',
        'r0-junit-inference.xml',
        'r0-junit-app.xml',
        'layered-report.json',
        'python-verifier-summary.json',
        'layered-r1-summary.json',
        'r1-junit-inference.xml',
        'r1-junit-app.xml'
    )
    $summary = [ordered]@{
        reportVersion = 'renderweave-layered-r1-gate/1.0'
        result = 'passed'
        assurance = 'A1+A2-strict-input'
        anchorRevision = 'c12f23d76a6fc76a6a38042ff89bbd166e6012b5'
        revision = $revision
        seams = [ordered]@{
            primary = 'normalized ArtifactSet + AcquisitionPolicy -> DocumentObservationIR/1.0'
            highestAcceptance = 'complete IMAGE_ONLY scripted replay -> REVIEW_REQUIRED'
        }
        architecture = [ordered]@{
            orchestration = 'existing-postgresql-durable-typed-state-machine'
            semanticStages = 'serial'
            localRepair = 'validator-driven-bounded'
            openEndedAgent = $false
            generalToolExecutor = $false
            langGraph = $false
            temporal = $false
        }
        scope = [ordered]@{
            r0Complete = $true
            r1Complete = $true
            template = $false
            rootDocumentConnect = $false
            dataAdaptation = $false
            publishing = $false
        }
        identities = [ordered]@{
            reportIdentity = $pythonSummary.reportIdentity
            evaluationIdentity = $pythonSummary.evaluationIdentity
            corpusIdentity = $pythonSummary.corpusIdentity
            annotationSetIdentity = $pythonSummary.annotationSetIdentity
            recordSetIdentity = $pythonSummary.recordSetIdentity
            caseAssignmentIdentity = $pythonSummary.caseAssignmentIdentity
            recomputedMetricsIdentity = $pythonSummary.recomputedMetricsIdentity
            corpusLockIdentity = $pythonSummary.corpusLockIdentity
        }
        crossLanguage = [ordered]@{
            java = 'PASS'
            python = 'PASS'
            exactIdentity = $true
            exactCaseAccounting = $true
            exactAllMetrics = $true
            caseCount = [int]$pythonSummary.caseCount
            metricCount = [int]$pythonSummary.metricCount
            sliceAggregateCount = [int]$pythonSummary.sliceAggregateCount
        }
        caseAccounting = [ordered]@{
            expected = 60
            observed = [int]$pythonSummary.caseCount
            partitions = $pythonSummary.partitions
            domains = $pythonSummary.domains
            difficulties = $pythonSummary.difficulties
            failureSlices = $pythonSummary.failureSlices
        }
        externalProvider = [ordered]@{
            attempts = 0
            reservations = 0
            costMicrosCny = 0
        }
        historicalBytes = [ordered]@{
            unchanged = $true
            protectedFiles = $protectedFiles
        }
        r0Prerequisite = [ordered]@{
            proofVersion = 'renderweave-layered-r0-prerequisite-proof/1.0'
            result = 'PASS'
            assurance = 'A2_STRICT_INPUT_REPLAY'
            reportFile = 'document-observation-r0-summary.json'
            reportSha256 = (Get-FileHash -LiteralPath $r0ReportPath -Algorithm SHA256).Hash.ToLowerInvariant()
            revision = $r0Summary.revision
            terminalState = $r0Summary.behaviorOracle.terminalState
            providerAttempts = [int]$r0Summary.externalProvider.attempts
            providerReservations = [int]$r0Summary.externalProvider.reservations
            externalProviderCostMicrosCny = [long]$r0Summary.externalProvider.costMicrosCny
        }
        lifecycle = [ordered]@{
            productV45 = 'EXPERIMENTAL'
            n7 = 'in_progress'
            ac021 = 'not_satisfied'
            acVr010 = 'not_satisfied'
            finalBusinessVisualJudgement = 'J0'
        }
        futureEvidenceGates = [ordered]@{
            R2 = [ordered]@{ triggered = $false; code = 'R2_BASELINE_GAP_AND_LICENSE_EVIDENCE_REQUIRED' }
            R3 = [ordered]@{ triggered = $false; code = 'R3_REPRODUCIBLE_ORDER_FAILURE_EVIDENCE_REQUIRED' }
            R4 = [ordered]@{ triggered = $false; code = 'R4_STRICT_PROTOCOL_AND_SHAPE_BOTTLENECK_EVIDENCE_REQUIRED' }
            R5 = [ordered]@{ triggered = $false; code = 'R5_STATIC_VIEW_BOTTLENECK_EVIDENCE_REQUIRED' }
            R6 = [ordered]@{ triggered = $false; code = 'R6_ORCHESTRATION_PRESSURE_EVIDENCE_REQUIRED' }
        }
        visualDiff = [ordered]@{
            scope = 'local-allowlisted-only'
            automatedEvidence = 'A1'
            humanReview = 'human_review_pending'
            judgement = 'J0'
            evidenceIncluded = $false
        }
        payloadScan = [ordered]@{
            result = 'passed'
            scanner = 'renderweave-layered-evidence-scanner/1.0'
            forbiddenMatches = 0
            files = $evidenceFiles
        }
    }
    Write-Utf8NewFile -Path $gateSummaryPath -Content (
        ($summary | ConvertTo-Json -Depth 14 -Compress) + "`n"
    )

    Invoke-Checked 'r1-independent-identity-accounting-and-payload-gate' {
        & python.exe tools/verify_layered_evaluation_gate.py `
            --report $reportPath `
            --verifier-summary $pythonSummaryPath `
            --gate-summary $gateSummaryPath `
            --r0-report $r0ReportPath `
            --repository $repoRoot `
            --evidence-file $r0InferenceJUnitPath `
            --evidence-file $r0AppJUnitPath `
            --evidence-file $inferenceJUnitPath `
            --evidence-file $appJUnitPath `
            --output $independentSummaryPath
    }
    if (-not (Test-Path -LiteralPath $independentSummaryPath -PathType Leaf)) {
        throw 'Independent layered R1 gate completed without producing its summary.'
    }
}
finally {
    [Environment]::SetEnvironmentVariable('RENDERWEAVE_RUN_LAYERED_R1_EVALUATION', $null, 'Process')
    [Environment]::SetEnvironmentVariable('RENDERWEAVE_LAYERED_R1_REPORT', $null, 'Process')
    Pop-Location
}

$independent = Get-Content -LiteralPath $independentSummaryPath -Raw -Encoding UTF8 |
    ConvertFrom-Json
Write-Host (
    ('Layered R1: result={0} assurance={1} cases={2} metrics={3} ' +
    'providerAttempts={4} visualDiff={5}') -f
    $independent.result, $independent.assurance, $independent.caseCount,
    $independent.metricCount, $independent.providerAttempts, $independent.visualDiffJudgement
)
Write-Host "Layered R1 evidence: $resolvedEvidenceDir"
