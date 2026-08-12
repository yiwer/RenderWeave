[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$reportCandidate = if ([System.IO.Path]::IsPathRooted($ReportPath)) {
    $ReportPath
} else {
    Join-Path $repoRoot $ReportPath
}
$resolvedReportPath = [System.IO.Path]::GetFullPath($reportCandidate)
$reportDirectory = Split-Path -Parent $resolvedReportPath
$null = New-Item -ItemType Directory -Path $reportDirectory -Force

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Invoke-Checked {
    param([string]$Name, [scriptblock]$Action)
    Write-Host "==> $Name"
    $global:LASTEXITCODE = 0
    & $Action
    if (-not $? -or $LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
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
        $null = $content.Append($document.DocumentElement.OuterXml)
    }
    $null = $content.Append('</testsuites>')
    Write-Utf8File -Path $Destination -Content $content.ToString()
}

# R0/R1 are zero-external-Provider work. Clear authorization and credential variables without
# reading their values, even when this script is invoked outside the parent gate wrapper.
@(
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

Push-Location $repoRoot
try {
    Invoke-Checked 'r0-independent-verifier-tests' {
        & python.exe tools/test_verify_document_observation_r0.py
    }
    Invoke-Checked 'r0-rapidocr-adapter-tests' {
        & python.exe tools/document-vision/test_rapidocr_adapter.py
    }
    Invoke-Checked 'r0-v45-projection-replay' {
        & python.exe tools/document-vision/verify_v45_projection.py
    }

    $testSelection = @(
        'DocumentVisionContractTest',
        'VisualEvidenceAcquisitionContractTest',
        'DocumentObservationCompatibilityProjectionTest',
        'DocumentObservationSuccessorIdentityTest',
        'StageResponseShapeCatalogTest',
        'LocalProcessDocumentVisionPreprocessorTest',
        'LocalProcessVisualEvidenceAcquisitionTest',
        'PostgresLiveInferenceWorkflowTest'
    ) -join ','
    Invoke-Checked 'r0-java-contract-differential-and-postgres-replay' {
        & mvn.cmd -B -ntp -pl renderweave-app -am `
            "-Dtest=$testSelection" `
            "-Dsurefire.failIfNoSpecifiedTests=false" test
    }

    $inferenceReports = @(
        'renderweave-inference/target/surefire-reports/TEST-cn.hbads.renderweave.inference.vision.DocumentVisionContractTest.xml',
        'renderweave-inference/target/surefire-reports/TEST-cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisitionContractTest.xml',
        'renderweave-inference/target/surefire-reports/TEST-cn.hbads.renderweave.inference.vision.DocumentObservationCompatibilityProjectionTest.xml',
        'renderweave-inference/target/surefire-reports/TEST-cn.hbads.renderweave.inference.eval.visual.DocumentObservationSuccessorIdentityTest.xml',
        'renderweave-inference/target/surefire-reports/TEST-cn.hbads.renderweave.inference.live.StageResponseShapeCatalogTest.xml'
    ) | ForEach-Object { Join-Path $repoRoot $_ }
    $appReports = @(
        'renderweave-app/target/surefire-reports/TEST-cn.hbads.renderweave.inference.LocalProcessDocumentVisionPreprocessorTest.xml',
        'renderweave-app/target/surefire-reports/TEST-cn.hbads.renderweave.inference.LocalProcessVisualEvidenceAcquisitionTest.xml',
        'renderweave-app/target/surefire-reports/TEST-cn.hbads.renderweave.inference.PostgresLiveInferenceWorkflowTest.xml'
    ) | ForEach-Object { Join-Path $repoRoot $_ }
    Merge-JUnitReports -Sources $inferenceReports `
        -Destination (Join-Path $reportDirectory 'r0-junit-inference.xml')
    Merge-JUnitReports -Sources $appReports `
        -Destination (Join-Path $reportDirectory 'r0-junit-app.xml')

    $protectedPaths = @(
        '.sdlc/live/visual-evaluation-qwen37-flash.json',
        '.sdlc/live/visual-evaluation-qwen37-plus.json',
        '.sdlc/live/visual-evaluation-qwen38-max.json',
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
        throw 'Unable to resolve the evidence revision.'
    }
    $report = [ordered]@{
        reportVersion = 'renderweave-document-observation-r0-gate/1.0'
        result = 'passed'
        assurance = 'A1+A2-strict-input'
        anchorRevision = '19e22854e0be236d0068336a32969356a6befaf8'
        revision = $revision
        seam = [ordered]@{
            input = 'normalized ArtifactSet + AcquisitionPolicy'
            output = 'DocumentObservationIR/1.0'
        }
        highestAcceptanceSeam = 'complete IMAGE_ONLY scripted replay -> REVIEW_REQUIRED'
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
            r1Enabled = $false
            template = $false
            rootDocumentConnect = $false
            dataAdaptation = $false
            publishing = $false
        }
        identities = [ordered]@{
            observationContract = 'DocumentObservationIR/1.0'
            acquisitionPolicyContract = 'AcquisitionPolicy/1.0'
            compatibilityProjection = 'v45-source-to-candidate/1.0'
            stageShapeCatalog = 'ad46adfbf6dc9e200f4736e693646ee485de5530af35b2f12802f561faa16557'
            successor = 'renderweave-document-observation-successor/1.0:302917d557bf7df9326b9a7d4af840c190be471041712806c19f932e24e1a3a2'
            candidateSemanticFingerprint = 'ee99eb03b4fd94a0970fe2db37041be30913a8d3c63f10d7bb64c3e27ee249a0'
        }
        behaviorOracle = [ordered]@{
            terminalState = 'REVIEW_REQUIRED'
            acceptedStages = @('OBSERVE', 'HIERARCHY', 'ELEMENT_BINDING')
            acceptedStageCanonicalPayload = 'byte-equivalent-by-locked-projection-and-unchanged-v45-codecs'
            acceptedStageReplayCountAfterRecovery = 0
            candidateSemanticSummary = 'root=document;schema=document(root)[title:TEXT:e1,items:ARRAY<REFERENCE:item>:e1];schema=item[ label:TEXT:e2@2300,6300 ]'
            fieldOrder = @('title', 'items', 'label')
            relationshipOrder = @('document-items:MANY')
            evidenceTopOrder = @(2300, 6300)
            fixedIssueRouting = @(
                'OBSERVE:VISUAL_SEMANTIC_OBSERVE_DOCUMENT_SEQUENCE_GROUP_MISSING',
                'ACQUISITION:DOCUMENT_VISION_PROJECTION_INVALID'
            )
            blockers = @([ordered]@{ code = 'LOW_CONFIDENCE_UNRESOLVED'; count = 5 })
            warningCount = 0
            scriptedReservationSummary = [ordered]@{
                attempts = 3
                settled = 3
                inputTokens = 3000
                outputTokens = 1500
                costMicrosCny = 18000
            }
        }
        coverage = @(
            'accepted-stage-no-replay',
            'cancellation-before-blob-read',
            'cmyk-explicit-bgr',
            'empty-output',
            'lease-expiry-ir-recompute',
            'limit-enforcement',
            'multi-image-order',
            'out-of-bounds',
            'payload-redaction',
            'png-jpeg',
            'repeated-instance-coalescing',
            'root-child-many-reference',
            'strong-document-sequence'
        )
        checks = @(
            'java-contract-property-differential',
            'postgres-cancellation',
            'postgres-lease-recovery',
            'postgres-scripted-replay',
            'protected-v45-byte-diff',
            'python-payload-scan',
            'rapidocr-adapter-python',
            'v45-projection-python'
        )
        externalProvider = [ordered]@{
            attempts = 0
            reservations = 0
            costMicrosCny = 0
        }
        testReports = @('r0-junit-inference.xml', 'r0-junit-app.xml')
        protectedFiles = $protectedFiles
        payloadScan = [ordered]@{
            result = 'passed'
            forbiddenMatches = 0
            categories = @(
                'checkpoint', 'database-row', 'evidence', 'exception', 'junit-report',
                'log', 'object-string', 'report', 'stderr', 'stdout'
            )
        }
    }
    Write-Utf8File -Path $resolvedReportPath -Content ($report | ConvertTo-Json -Depth 12)

    Invoke-Checked 'r0-independent-replay-and-payload-scan' {
        & python.exe tools/verify_document_observation_r0.py `
            --report $resolvedReportPath --repository $repoRoot
    }
}
finally {
    Pop-Location
}

Write-Host "R0 report: $resolvedReportPath"
