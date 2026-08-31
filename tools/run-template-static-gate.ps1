[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$candidateEvidenceDir = [System.IO.Path]::GetFullPath(
    $(if ([System.IO.Path]::IsPathRooted($EvidenceDir)) { $EvidenceDir } else { Join-Path $repoRoot $EvidenceDir })
)
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $candidateEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Template evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Template evidence directory must already exist.'
}
$evidenceAttributes = [System.IO.File]::GetAttributes($candidateEvidenceDir)
if (($evidenceAttributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Template evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Template evidence directory escapes .sdlc/evidence.'
}

$summaryPath = Join-Path $resolvedEvidenceDir 'template-static-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'Template static summary already exists.'
}

$sourceSpecRoot = (Resolve-Path (
        Join-Path $repoRoot '.scratch\renderweave-template-v1'
    )).Path
$repoGitDirOutput = @(& git.exe -C $repoRoot rev-parse --absolute-git-dir)
if ($LASTEXITCODE -ne 0 -or $repoGitDirOutput.Count -eq 0) {
    throw 'Template replay cannot resolve the source repository object database.'
}
$repoGitDir = [System.IO.Path]::GetFullPath($repoGitDirOutput[-1].Trim())
if (-not (Test-Path -LiteralPath $repoGitDir -PathType Container)) {
    throw 'Template replay source repository object database is unavailable.'
}
$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd($separator)
$tempRepoRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $tempBase ('renderweave-template-static-' + [Guid]::NewGuid().ToString('N')))
)
if (-not $tempRepoRoot.StartsWith(
        $tempBase + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Template replay directory must be below the system temporary directory.'
}

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
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

function Get-TreeManifest {
    param([Parameter(Mandatory = $true)][string]$Root)
    $resolvedRoot = (Resolve-Path -LiteralPath $Root).Path.TrimEnd($separator)
    $prefixLength = $resolvedRoot.Length + 1
    $rows = foreach ($file in Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File | Sort-Object FullName) {
        $relativePath = $file.FullName.Substring($prefixLength).Replace($separator, '/')
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($file.Length)  $relativePath"
    }
    return @($rows)
}

# This gate is fully offline. Clear credential selectors without reading or displaying values.
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

$trickyFontReportPath = Join-Path $resolvedEvidenceDir 'renderer-tricky-font-compatibility.json'
$trickyFontFixtureReportPath = Join-Path $resolvedEvidenceDir 'renderer-tricky-font-fixture.json'
Invoke-Checked 'renderer-tricky-font-compatibility' {
    & (Join-Path $PSScriptRoot 'run-renderer-tricky-font-compatibility-gate.ps1') `
        -EvidenceDir $resolvedEvidenceDir
}
$trickyFontReport = Get-Content -Raw -Encoding UTF8 -LiteralPath $trickyFontReportPath |
    ConvertFrom-Json
if ($trickyFontReport.status -ne 'PASS_SUCCESSOR_MECHANICALLY_BUILDABLE_BUILD_PENDING' `
        -or $trickyFontReport.decisionStatus -ne 'SUCCESSOR_MECHANICAL_CONFIGURATION_VALID_EXACT_BUILD_PENDING' `
        -or $trickyFontReport.failureCount -ne 0 `
        -or -not $trickyFontReport.observedCompatibility.stockOptionsReachable `
        -or $trickyFontReport.observedCompatibility.optionsHeaderSelfShadowing `
        -or -not $trickyFontReport.observedCompatibility.moduleListRepeatable `
        -or $trickyFontReport.observedCompatibility.t213AdapterRequired `
        -or -not $trickyFontReport.observedCompatibility.currentCandidateMechanicallyBuildable `
        -or $trickyFontReport.observedCompatibility.runtimeBytecodeNonExecutionProven `
        -or $trickyFontReport.observedCompatibility.exactBuiltTargetObserved `
        -or $trickyFontReport.boundary.exactBuiltTargetObserved `
        -or $trickyFontReport.boundary.rendererExactOutputRecordIssuanceAllowed `
        -or $trickyFontReport.boundary.certified `
        -or $trickyFontReport.boundary.ready `
        -or $trickyFontReport.boundary.ticket19MayClose) {
    throw 'Renderer tricky-font compatibility did not remain fail closed.'
}
$trickyFontFixtureReport = Get-Content -Raw -Encoding UTF8 -LiteralPath $trickyFontFixtureReportPath |
    ConvertFrom-Json
if ($trickyFontFixtureReport.status -ne 'PASS_PORTABLE_TRICKY_FONT_FIXTURE_SOURCE_VERIFIED_BUILD_PENDING' `
        -or $trickyFontFixtureReport.failureCount -ne 0 `
        -or -not $trickyFontFixtureReport.reproducible `
        -or $trickyFontFixtureReport.classification.matchedToken -ne 'cpop' `
        -or -not $trickyFontFixtureReport.classification.expectedFtIsTricky `
        -or $trickyFontFixtureReport.boundary.exactBuiltTargetObserved `
        -or $trickyFontFixtureReport.boundary.runtimeBytecodeNonExecutionProven `
        -or $trickyFontFixtureReport.boundary.noHintingVersusNoAutoHintDistinguished `
        -or $trickyFontFixtureReport.boundary.physicalLinuxReplayComplete `
        -or $trickyFontFixtureReport.boundary.rendererExactOutputRecordIssuanceAllowed `
        -or $trickyFontFixtureReport.boundary.certified `
        -or $trickyFontFixtureReport.boundary.ready `
        -or $trickyFontFixtureReport.boundary.ticket19MayClose) {
    throw 'Renderer portable tricky-font fixture did not remain fail closed.'
}

$sourceManifest = @(Get-TreeManifest -Root $sourceSpecRoot)
$tempSpecRoot = $null
try {
    $null = New-Item -ItemType Directory -Path (Join-Path $tempRepoRoot '.scratch') -Force
    Copy-Item -LiteralPath $sourceSpecRoot -Destination (Join-Path $tempRepoRoot '.scratch') -Recurse
    $tempSpecRoot = (Resolve-Path (
            Join-Path $tempRepoRoot '.scratch\renderweave-template-v1'
        )).Path

    $initialCopyManifest = @(Get-TreeManifest -Root $tempSpecRoot)
    $copyDifference = @(Compare-Object -ReferenceObject $sourceManifest -DifferenceObject $initialCopyManifest)
    if ($copyDifference.Count -ne 0) {
        throw 'Template replay copy does not match the source authority bytes.'
    }

    Push-Location $tempRepoRoot
    try {
        Invoke-Checked 'template-editor-primary-generate-and-replay' {
            & node.exe '.scratch\renderweave-template-v1\editor-automated\generate-editor-atomic-candidates.mjs'
        }
        Invoke-Checked 'template-editor-independent-replay' {
            & python.exe '.scratch\renderweave-template-v1\editor-automated\validate_editor_atomic_candidates_independent.py'
        }
        Invoke-Checked 'template-editor-a2-writer' {
            & node.exe '.scratch\renderweave-template-v1\editor-automated\write-editor-atomic-candidate-evidence.mjs'
        }
    }
    finally {
        Pop-Location
    }

    $previousGitDir = [Environment]::GetEnvironmentVariable('GIT_DIR', 'Process')
    $previousGitWorkTree = [Environment]::GetEnvironmentVariable('GIT_WORK_TREE', 'Process')
    [Environment]::SetEnvironmentVariable('GIT_DIR', $repoGitDir, 'Process')
    # Git for Windows may stat a long revision:path against the current directory before
    # resolving it as an object name. Bind the short, real work tree while replay code
    # continues to read all authority bytes from the isolated temp copy.
    [Environment]::SetEnvironmentVariable('GIT_WORK_TREE', $repoRoot, 'Process')
    Push-Location $tempSpecRoot
    try {
        Invoke-Checked 'template-rendering-pipeline-postissuance-replay' {
            & node.exe 'rendering-pipeline\write-rendering-pipeline-postissuance-a2-evidence.mjs'
        }
        Invoke-Checked 'template-registry-refresh-target' {
            & node.exe 'spec-registry\refresh-spec-registry-postissuance-target.mjs'
        }
        Invoke-Checked 'template-registry-primary-replay' {
            & node.exe 'spec-registry\validate-spec-registry-primary.mjs' `
                '--target' 'spec-registry/target-manifest-v1.json'
        }
        Invoke-Checked 'template-registry-independent-replay' {
            & python.exe 'spec-registry\validate-spec-registry-independent.py' `
                '--target' 'spec-registry/target-manifest-v1.json'
        }
        Invoke-Checked 'template-registry-a2-writer' {
            & node.exe 'spec-registry\write-spec-registry-a2-evidence.mjs'
        }
    }
    finally {
        Pop-Location
        [Environment]::SetEnvironmentVariable('GIT_DIR', $previousGitDir, 'Process')
        [Environment]::SetEnvironmentVariable('GIT_WORK_TREE', $previousGitWorkTree, 'Process')
    }

    $replayedManifest = @(Get-TreeManifest -Root $tempSpecRoot)
    $authorityDifference = @(
        Compare-Object -ReferenceObject $sourceManifest -DifferenceObject $replayedManifest
    )
    if ($authorityDifference.Count -ne 0) {
        Write-Host 'Template replay changed authority bytes:'
        $authorityDifference | ForEach-Object { Write-Host $_.InputObject $_.SideIndicator }
        throw 'Template replay is not byte-identical; repository authority was not modified.'
    }

    $editorPrimary = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'editor-automated\atomic-candidate-primary-result-v1.json'
    ) | ConvertFrom-Json
    $editorIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'editor-automated\atomic-candidate-independent-result-v1.json'
    ) | ConvertFrom-Json
    $registryPrimary = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'spec-registry\primary-result-v1.json'
    ) | ConvertFrom-Json
    $registryIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'spec-registry\independent-result-v1.json'
    ) | ConvertFrom-Json
    $registryA2 = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'spec-registry\spec-registry-a2-2026-08-17.json'
    ) | ConvertFrom-Json
    $designPrimary = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'design-input-expression\postissuance-primary-result-v1.json'
    ) | ConvertFrom-Json
    $designIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'design-input-expression\postissuance-independent-result-v1.json'
    ) | ConvertFrom-Json
    $designA2 = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'design-input-expression\design-input-expression-capacity-postissuance-a2-2026-08-29.json'
    ) | ConvertFrom-Json
    $renderingPrimary = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'rendering-pipeline\postissuance-primary-result-v1.json'
    ) | ConvertFrom-Json
    $renderingIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'rendering-pipeline\postissuance-independent-result-v1.json'
    ) | ConvertFrom-Json
    $renderingA2 = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'rendering-pipeline\rendering-pipeline-capacity-postissuance-a2-2026-08-29.json'
    ) | ConvertFrom-Json
    $targetManifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'spec-registry\target-manifest-v1.json'
    ) | ConvertFrom-Json
    $acceptanceManifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'acceptance-manifest-v1.json'
    ) | ConvertFrom-Json
    $candidateProbeProfile = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'editor-automated\probe-profile-candidate-v1_1.json'
    ) | ConvertFrom-Json
    $contentSourceCatalog = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'editor-automated\content-source-catalog-v1.json'
    ) | ConvertFrom-Json
    $contentSourceRecord = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $tempSpecRoot 'editor-automated\content-sources\ecs-j10-012-pa011.json'
    ) | ConvertFrom-Json
    $editorInventory = $acceptanceManifest.nonCapacityAtomicCorpus.editorRoutingInventory
    $issuedProbeProfile = $acceptanceManifest.conformanceRegistries.probeProfile
    $ticket19 = @($targetManifest.artifacts | Where-Object {
            $_.path -eq 'issues/19-security-capacity-acceptance.md'
        })

    if ($editorPrimary.status -ne 'PASS_STATIC_CANDIDATE_DECOMPOSITION' `
            -or $editorPrimary.failureCount -ne 0 `
            -or $editorIndependent.status -ne 'PASS_STATIC_CANDIDATE_DECOMPOSITION' `
            -or $editorIndependent.failureCount -ne 0) {
        throw 'Template Editor static replay did not pass.'
    }
    if ($editorIndependent.observed.candidateCount -ne 108 `
            -or $editorIndependent.observed.assertionPlanCount -ne 1265 `
            -or $editorIndependent.observed.exactExpectationAssertionPlanCount -ne 1146 `
            -or $editorIndependent.observed.pendingExpectationAssertionPlanCount -ne 119 `
            -or $editorIndependent.observed.contentSourceExactBindingCount -ne 1 `
            -or $editorIndependent.observed.contentSourceUnboundBindingCount -ne 46) {
        throw 'Template Editor frozen planning counts drifted.'
    }
    if ($editorInventory.formalCaseCount -ne 0 -or $editorInventory.formalOracleCount -ne 0 `
            -or $issuedProbeProfile.candidateProbeProfileId -ne 'renderweave-conformance-probes/1.0' `
            -or $issuedProbeProfile.sha256 -ne 'f800eb1e6e138215c26c7761ed80e0fc9cf77fc3ce051be4e3c5ba530cd6053d' `
            -or $issuedProbeProfile.probeCount -ne 110 -or -not $issuedProbeProfile.recordMayReference `
            -or $candidateProbeProfile.candidateProbeProfileId -ne 'renderweave-conformance-probes/1.1' `
            -or $candidateProbeProfile.status -ne 'COMPLETE_CANDIDATE_NOT_ISSUED' `
            -or $candidateProbeProfile.probeCount -ne 119 -or $candidateProbeProfile.recordMayReference) {
        throw 'Template Editor formal-record or Probe Profile boundary drifted.'
    }
    if ($contentSourceCatalog.counts.sourceSlotCount -ne 47 `
            -or $contentSourceCatalog.counts.exactSourceBindingCount -ne 1 `
            -or $contentSourceCatalog.counts.unboundSourceBindingCount -ne 46 `
            -or $contentSourceRecord.sourceSlotId -ne 'ECS::J10::012::PA011' `
            -or $contentSourceRecord.candidateId -ne 'EDC::J10::012' `
            -or $contentSourceRecord.planAssertionId -ne 'PA011' `
            -or $contentSourceRecord.stateProof.expectedTerminal.operationId -ne 'replaceWorkingDraft' `
            -or $contentSourceRecord.stateProof.expectedTerminal.outcome -ne 'NONTERMINAL_REJECTION' `
            -or $contentSourceRecord.stateProof.expectedTerminal.code -ne 'EDITOR_DIRTY_DRAFT_REPLACEMENT_BLOCKED' `
            -or $contentSourceRecord.stateProof.postActionRule -ne 'BYTE_IDENTICAL_TO_PRE_ACTION') {
        throw 'Template Editor exact content-source binding drifted.'
    }
    if ($designPrimary.status -ne 'PASS' -or $designPrimary.failureCount -ne 0 `
            -or $designPrimary.issuedDesignInputExpressionCaseCount -ne 195 `
            -or $designPrimary.issuedDesignInputExpressionOracleCount -ne 195 `
            -or $designPrimary.issuedCapacityCaseCount -ne 207 `
            -or $designIndependent.status -ne 'PASS' -or $designIndependent.failureCount -ne 0 `
            -or $designIndependent.issuedDesignInputExpressionCaseCount -ne 195 `
            -or $designIndependent.issuedDesignInputExpressionOracleCount -ne 195 `
            -or $designIndependent.issuedCapacityCaseCount -ne 207 `
            -or $designA2.status -ne 'PASS' -or $designA2.grade -ne 'A2_INDEPENDENTLY_REPLAYED' `
            -or -not $designA2.observedFrontier.designInputExpressionExecutable `
            -or $designA2.observedFrontier.totalFormalCaseCount -ne 253 `
            -or $designA2.observedFrontier.totalFormalOracleCount -ne 253) {
        throw 'Template Design/Input/Expression post-issuance replay did not pass.'
    }
    if ($renderingPrimary.status -ne 'PASS' -or $renderingPrimary.failureCount -ne 0 `
            -or $renderingPrimary.issuedRenderingPipelineCaseCount -ne 156 `
            -or $renderingPrimary.issuedRenderingPipelineOracleCount -ne 156 `
            -or $renderingPrimary.issuedCapacityCaseCount -ne 363 `
            -or $renderingIndependent.status -ne 'PASS' -or $renderingIndependent.failureCount -ne 0 `
            -or $renderingIndependent.issuedRenderingPipelineCaseCount -ne 156 `
            -or $renderingIndependent.issuedRenderingPipelineOracleCount -ne 156 `
            -or $renderingIndependent.issuedCapacityCaseCount -ne 363 `
            -or $renderingA2.status -ne 'PASS' -or $renderingA2.grade -ne 'A2_INDEPENDENTLY_REPLAYED' `
            -or -not $renderingA2.observedFrontier.renderingPipelineExecutable `
            -or $renderingA2.observedFrontier.totalFormalCaseCount -ne 409 `
            -or $renderingA2.observedFrontier.totalFormalOracleCount -ne 409) {
        throw 'Template Rendering Pipeline postissuance replay did not pass.'
    }
    if ($registryPrimary.status -ne 'PASS' -or $registryPrimary.checkCount -lt 22838 `
            -or $registryPrimary.failureCount -ne 0 `
            -or $registryIndependent.status -ne 'PASS' `
            -or $registryIndependent.checkCount -lt 22746 `
            -or $registryIndependent.failureCount -ne 0) {
        throw 'Template SPEC_REGISTRY replay counts or status drifted.'
    }
    if ($targetManifest.implementationRevision -ne 'spec-registry-bootstrap/1.16' `
            -or $targetManifest.artifacts.Count -lt 404 -or $ticket19.Count -ne 1 `
            -or $ticket19[0].sha256 -ne 'sha256:ce7335f4b50ad23fb77b018cea1d9d89d94c11e02c72da13e1d392b13a065cae' `
            -or $ticket19[0].byteLength -ne 74549) {
        throw 'Template target manifest or Ticket 19 LF blob binding drifted.'
    }
    if ($registryA2.status -ne 'PASS' `
            -or $registryA2.grade -ne 'A2_INDEPENDENTLY_REPLAYED' `
            -or $registryA2.observedFrontier.issuedSpecCaseCount -ne 46 `
            -or $registryA2.observedFrontier.issuedSpecOracleCount -ne 46 `
            -or $registryA2.observedFrontier.capacityAxisCount -ne 175 `
            -or $registryA2.observedFrontier.capacityShapeCandidateCaseCount -ne 525 `
            -or $registryA2.observedFrontier.capacityShapeCandidateOracleCount -ne 525 `
            -or $registryA2.observedFrontier.capacityRecordsIssued -ne 363 `
            -or $registryA2.boundary.rendererCertified `
            -or $registryA2.boundary.rendererReady `
            -or $registryA2.boundary.ticket19Closed) {
        throw 'Template A2 evidence boundary drifted or over-claimed readiness.'
    }
    if ($acceptanceManifest.counts.registeredRequirements -ne 3651 `
            -or $acceptanceManifest.counts.plannedContractBoundaryCases -ne 525 `
            -or $acceptanceManifest.counts.minimumCombinedWorstPathCases -ne 18 `
            -or $acceptanceManifest.counts.strictContractBoundaryFloor -ne 543 `
            -or $acceptanceManifest.counts.executableContractBoundaryCases -ne 363) {
        throw 'Template requirement or capacity planning counts drifted.'
    }

    $summary = [ordered]@{
        gateVersion = 'renderweave-template-static-gate/1.0'
        status = 'PASS'
        sourceFileCount = $sourceManifest.Count
        authorityByteIdenticalAfterReplay = $true
        rendererTrickyFontCompatibility = [ordered]@{
            status = $trickyFontReport.status
            decisionStatus = $trickyFontReport.decisionStatus
            candidateId = $trickyFontReport.candidateId
            checkCount = $trickyFontReport.checkCount
            stockOptionsReachable = [bool]$trickyFontReport.observedCompatibility.stockOptionsReachable
            moduleListRepeatable = [bool]$trickyFontReport.observedCompatibility.moduleListRepeatable
            runtimeBytecodeNonExecutionProven = [bool]$trickyFontReport.observedCompatibility.runtimeBytecodeNonExecutionProven
            exactBuiltTargetObserved = [bool]$trickyFontReport.boundary.exactBuiltTargetObserved
            recordIssuanceAllowed = [bool]$trickyFontReport.boundary.rendererExactOutputRecordIssuanceAllowed
            certified = [bool]$trickyFontReport.boundary.certified
            ready = [bool]$trickyFontReport.boundary.ready
            ticket19MayClose = [bool]$trickyFontReport.boundary.ticket19MayClose
            portableFixture = [ordered]@{
                status = $trickyFontFixtureReport.status
                fixtureId = $trickyFontFixtureReport.fixtureId
                sha256 = $trickyFontFixtureReport.fixture.sha256
                byteLength = $trickyFontFixtureReport.fixture.byteLength
                tableCount = $trickyFontFixtureReport.fixture.tableCount
                checkCount = $trickyFontFixtureReport.checkCount
                reproducible = [bool]$trickyFontFixtureReport.reproducible
                matchedToken = $trickyFontFixtureReport.classification.matchedToken
                exactBuiltTargetObserved = [bool]$trickyFontFixtureReport.boundary.exactBuiltTargetObserved
                runtimeBytecodeNonExecutionProven = [bool]$trickyFontFixtureReport.boundary.runtimeBytecodeNonExecutionProven
                certified = [bool]$trickyFontFixtureReport.boundary.certified
                ready = [bool]$trickyFontFixtureReport.boundary.ready
            }
        }
        editor = [ordered]@{
            candidateCount = $editorIndependent.observed.candidateCount
            assertionPlanCount = $editorIndependent.observed.assertionPlanCount
            exactExpectationCount = $editorIndependent.observed.exactExpectationAssertionPlanCount
            pendingExpectationCount = $editorIndependent.observed.pendingExpectationAssertionPlanCount
            contentSourceExactCount = $editorIndependent.observed.contentSourceExactBindingCount
            contentSourceUnboundCount = $editorIndependent.observed.contentSourceUnboundBindingCount
            formalCaseCount = $editorInventory.formalCaseCount
            formalOracleCount = $editorInventory.formalOracleCount
            issuedProbeProfileId = $issuedProbeProfile.candidateProbeProfileId
            issuedProbeCount = $issuedProbeProfile.probeCount
            issuedProbeProfileSha256 = $issuedProbeProfile.sha256
            candidateProbeProfileId = $candidateProbeProfile.candidateProbeProfileId
            candidateProbeCount = $candidateProbeProfile.probeCount
            candidateProbeProfileRecordMayReference = [bool]$candidateProbeProfile.recordMayReference
            primaryCheckCount = $editorPrimary.checkCount
            independentCheckCount = $editorIndependent.checkCount
        }
        registry = [ordered]@{
            revision = $targetManifest.implementationRevision
            artifactCount = $targetManifest.artifacts.Count
            primaryCheckCount = $registryPrimary.checkCount
            independentCheckCount = $registryIndependent.checkCount
            primaryRuntime = $registryPrimary.runtime
            independentRuntime = $registryIndependent.runtime
            targetSha256 = $registryPrimary.targetManifest.sha256
            ticket19Sha256 = $ticket19[0].sha256
            ticket19ByteLength = $ticket19[0].byteLength
            registeredRequirementCount = $acceptanceManifest.counts.registeredRequirements
            capacityAxisCount = $registryA2.observedFrontier.capacityAxisCount
            strictCapacityFloor = $acceptanceManifest.counts.strictContractBoundaryFloor
        }
        designInputExpression = [ordered]@{
            primaryCheckCount = $designPrimary.checkCount
            independentCheckCount = $designIndependent.checkCount
            issuedCaseCount = $designPrimary.issuedDesignInputExpressionCaseCount
            issuedOracleCount = $designPrimary.issuedDesignInputExpressionOracleCount
            formalCaseCount = $designA2.observedFrontier.totalFormalCaseCount
            formalOracleCount = $designA2.observedFrontier.totalFormalOracleCount
            executable = [bool]$designA2.observedFrontier.designInputExpressionExecutable
        }
        renderingPipeline = [ordered]@{
            primaryCheckCount = $renderingPrimary.checkCount
            independentCheckCount = $renderingIndependent.checkCount
            issuedCaseCount = $renderingPrimary.issuedRenderingPipelineCaseCount
            issuedOracleCount = $renderingPrimary.issuedRenderingPipelineOracleCount
            formalCaseCount = $renderingA2.observedFrontier.totalFormalCaseCount
            formalOracleCount = $renderingA2.observedFrontier.totalFormalOracleCount
            executable = [bool]$renderingA2.observedFrontier.renderingPipelineExecutable
        }
        boundary = [ordered]@{
            productCodeExecuted = $false
            browserStarted = $false
            webServiceStarted = $false
            networkUsed = $false
            providerAttempts = 0
            formalEditorCaseCount = $editorInventory.formalCaseCount
            formalEditorOracleCount = $editorInventory.formalOracleCount
            capacityRecordsIssued = $registryA2.observedFrontier.capacityRecordsIssued
            rendererReady = [bool]$registryA2.boundary.rendererReady
            ticket19Closed = [bool]$registryA2.boundary.ticket19Closed
        }
    }
    Write-Utf8File -Path $summaryPath -Content ($summary | ConvertTo-Json -Depth 6)
    Write-Host (
        'Template static: editor={0}/{1} registry={2}/{3} artifacts={4} authorityDiff=0' -f
        $editorPrimary.checkCount, $editorIndependent.checkCount,
        $registryPrimary.checkCount, $registryIndependent.checkCount,
        $targetManifest.artifacts.Count
    )
    Write-Host "Template static evidence: $summaryPath"
}
finally {
    if (Test-Path -LiteralPath $tempRepoRoot) {
        $resolvedTempRepoRoot = (Resolve-Path -LiteralPath $tempRepoRoot).Path
        if (-not $resolvedTempRepoRoot.StartsWith(
                $tempBase + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to remove a Template replay directory outside the system temporary directory.'
        }
        Remove-Item -LiteralPath $resolvedTempRepoRoot -Recurse -Force
    }
}
