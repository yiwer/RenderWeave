[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$mvn = (Get-Command mvn.cmd -ErrorAction Stop).Source
$inferenceTestClasses = @(
    'ReplayCorpusEvaluationTest',
    'GroundedPipelineEvaluationTest',
    'LiveCandidateEvaluatorTest',
    'JsonCandidateProfilerTest',
    'JsonGroundedCandidateComposerTest',
    'LiveRepairPolicyTest',
    'VisualStageCorpusTest',
    'VisualStageEvaluatorTest',
    'VisualStageReporterTest',
    'VisualStageCheckpointReaderTest'
)
$applicationTestClasses = @(
    'DashScopeVisualEvaluationBatchLimitTest',
    'VisualEvaluationGoalBudgetTest',
    'VisualEvaluationJournalTest',
    'VisualEvaluationIdentityTest',
    'VisualEvaluationEvidenceVerifierTest',
    'VisualEvaluationAuthorizationLocatorTest'
)

# This gate remains deliberately offline. Assigning empty values in this child PowerShell process
# neither reads nor mutates the operator's parent-process secrets.
$env:DASHSCOPE_API_KEY = ''
$env:DASHSCOPE_API_KEY_FILE = ''
$env:RENDERWEAVE_RUN_LIVE_CANARY = ''
$env:RENDERWEAVE_RUN_LIVE_CERTIFICATION = ''
$env:RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION = ''
$env:RENDERWEAVE_RUN_VISUAL_EVALUATION = ''
$env:RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION = ''
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

Push-Location $repoRoot
try {
    $selector = $inferenceTestClasses -join ','
    & $mvn -B -ntp -pl renderweave-inference -am `
        "-Dtest=$selector" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "Offline inference evaluation tests failed with exit code $LASTEXITCODE."
    }

    $selector = $applicationTestClasses -join ','
    & $mvn -B -ntp -pl renderweave-app -am `
        "-Dtest=$selector" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "Offline visual evidence tests failed with exit code $LASTEXITCODE."
    }

    $testTargets = @(
        $inferenceTestClasses | ForEach-Object {
            [pscustomobject]@{ className = $_; module = 'renderweave-inference' }
        }
        $applicationTestClasses | ForEach-Object {
            [pscustomobject]@{ className = $_; module = 'renderweave-app' }
        }
    )
    $reports = foreach ($target in $testTargets) {
        $className = $target.className
        $path = Get-ChildItem -Path (Join-Path $repoRoot "$($target.module)\target\surefire-reports") `
            -Filter "TEST-*.$className.xml" -File | Select-Object -First 1
        if ($null -eq $path) {
            throw "Missing Surefire report for $className."
        }
        [xml]$document = Get-Content -Raw -Encoding UTF8 -LiteralPath $path.FullName
        $suite = $document.testsuite
        $tests = [int]$suite.tests
        $failures = [int]$suite.failures
        $errors = [int]$suite.errors
        $skipped = if ($null -eq $suite.skipped -or [string]::IsNullOrWhiteSpace([string]$suite.skipped)) {
            0
        }
        else {
            [int]$suite.skipped
        }
        if ($failures -ne 0 -or $errors -ne 0) {
            throw "$className report contains failures or errors."
        }
        [pscustomobject][ordered]@{
            className = $className
            tests = $tests
            failures = $failures
            errors = $errors
            skipped = $skipped
            durationSeconds = [double]$suite.time
        }
    }

    $summary = [ordered]@{
        schemaVersion = 'renderweave-offline-eval/2'
        result = 'PASS'
        evaluationMode = 'OFFLINE_DETERMINISTIC'
        corpora = @(
            [ordered]@{
                corpusVersion = 'renderweave-live-eval-v2'
                corpusCases = 60
                modeDistribution = [ordered]@{ IMAGE_ONLY = 20; JSON_ONLY = 20; COMBINED = 20 }
            },
            [ordered]@{
                corpusVersion = 'renderweave-visual-stage-corpus/1.0'
                corpusCases = 60
                partitionDistribution = [ordered]@{ DEV = 45; HOLDOUT = 15 }
                visualStyles = 5
                minimumHierarchyDepth = 3
            }
        )
        modeDistribution = [ordered]@{ IMAGE_ONLY = 20; JSON_ONLY = 20; COMBINED = 20 }
        groundedPipelineCases = [ordered]@{ JSON_ONLY = 20; COMBINED = 20 }
        providerAttempts = 0
        providerReservations = 0
        externalNetworkRequired = $false
        assertions = @(
            'balanced versioned synthetic corpus',
            'strict evidence-backed replay candidates',
            'whole-graph evaluator and certification policy adversarial negatives',
            'deterministic JSON grounding and safe visual overlay',
            'repair policy and ambiguous provider graph fail-closed behavior',
            'deterministic 60-case visual raster corpus with stage-separated metrics',
            'cross-ledger model token and cost reservation before delegation',
            'operator can narrow but never widen the authorized live batch',
            'durable interruption-safe visual journal with payload-free evidence',
            'independent Python aggregate and tamper verifier'
        )
        testClasses = @($reports)
        totalTests = [int](($reports | Measure-Object -Property tests -Sum).Sum)
        totalSkipped = [int](($reports | Measure-Object -Property skipped -Sum).Sum)
        generatedAt = (Get-Date).ToString('o')
    }
    $directory = Split-Path -Parent $ReportPath
    $null = New-Item -ItemType Directory -Path $directory -Force
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        $ReportPath,
        ($summary | ConvertTo-Json -Depth 6),
        $encoding
    )
}
finally {
    Pop-Location
}
