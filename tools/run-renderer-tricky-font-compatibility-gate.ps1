[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$candidateEvidenceDir = [System.IO.Path]::GetFullPath(
    $(if ([System.IO.Path]::IsPathRooted($EvidenceDir)) {
            $EvidenceDir
        }
        else {
            Join-Path $repoRoot $EvidenceDir
        })
)
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $candidateEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Renderer tricky-font compatibility evidence must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Renderer tricky-font compatibility evidence directory must already exist.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Renderer tricky-font compatibility evidence directory escapes .sdlc/evidence.'
}

$report = Join-Path $resolvedEvidenceDir 'renderer-tricky-font-compatibility.json'
if (Test-Path -LiteralPath $report) {
    throw "Renderer tricky-font compatibility evidence already exists: $report"
}
$fixtureReport = Join-Path $resolvedEvidenceDir 'renderer-tricky-font-fixture.json'
if (Test-Path -LiteralPath $fixtureReport) {
    throw "Renderer tricky-font fixture evidence already exists: $fixtureReport"
}

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

$pythonVersion = (& py.exe -3.13 --version 2>&1).Trim()
if ($LASTEXITCODE -ne 0 -or -not $pythonVersion.StartsWith('Python 3.13.')) {
    throw "Python 3.13 is required for Renderer tricky-font compatibility replay; got $pythonVersion."
}

Push-Location $repoRoot
try {
    @(
        'tools\test_verify_renderer_tricky_font_compatibility.py',
        'tools\test_verify_renderer_tricky_font_candidate_v2.py',
        'tools\test_verify_renderer_tricky_font_candidate_v3.py'
    ) | ForEach-Object {
        & py.exe -3.13 $_
        if ($LASTEXITCODE -ne 0) {
            throw "Renderer tricky-font candidate mutation tests failed for $_ with exit code $LASTEXITCODE."
        }
    }
    & py.exe -3.13 'tools\test_verify_renderer_tricky_font_fixture.py'
    if ($LASTEXITCODE -ne 0) {
        throw "Renderer tricky-font fixture mutation tests failed with exit code $LASTEXITCODE."
    }
    & py.exe -3.13 'tools\generate-renderer-tricky-font-fixture.py' '--check'
    if ($LASTEXITCODE -ne 0) {
        throw "Renderer tricky-font fixture reproduction failed with exit code $LASTEXITCODE."
    }
    & py.exe -3.13 'tools\verify-renderer-tricky-font-fixture.py' `
        '--repo' $repoRoot `
        '--report' $fixtureReport
    if ($LASTEXITCODE -ne 0) {
        throw "Renderer tricky-font fixture verifier failed with exit code $LASTEXITCODE."
    }
    & py.exe -3.13 'tools\verify-renderer-tricky-font-compatibility.py' `
        '--repo' $repoRoot `
        '--decision' '.scratch/renderweave-template-v1/renderer-spike/tricky-font-compatibility-decision-v3.json' `
        '--report' $report
    if ($LASTEXITCODE -ne 0) {
        throw "Renderer tricky-font compatibility verifier failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$fixtureResult = Get-Content -Raw -Encoding UTF8 -LiteralPath $fixtureReport | ConvertFrom-Json
if ($fixtureResult.reportVersion -ne 'renderweave-renderer-tricky-font-fixture-gate/1.0' `
        -or $fixtureResult.status -ne 'PASS_PORTABLE_TRICKY_FONT_FIXTURE_SOURCE_VERIFIED_BUILD_PENDING' `
        -or $fixtureResult.fixtureId -ne 'rw-renderer-portable-tricky-font-v1' `
        -or $fixtureResult.candidateId -ne 'rw-renderer-spike-linux-x86_64-v2-000002' `
        -or $fixtureResult.checkCount -lt 300 `
        -or $fixtureResult.failureCount -ne 0 `
        -or -not $fixtureResult.reproducible `
        -or $fixtureResult.fixture.sha256 -ne 'sha256:315504d5386a2e53f0c96cd3efbf71b9ccc3b1fef237dbec9e7d25cdbcf7139f' `
        -or $fixtureResult.fixture.byteLength -ne 996 `
        -or $fixtureResult.fixture.tableCount -ne 13 `
        -or $fixtureResult.classification.path -ne 'FAMILY_NAME_SUBSTRING' `
        -or $fixtureResult.classification.matchedToken -ne 'cpop' `
        -or -not $fixtureResult.classification.expectedFtIsTricky `
        -or $fixtureResult.boundary.exactBuiltTargetObserved `
        -or $fixtureResult.boundary.runtimeBytecodeNonExecutionProven `
        -or $fixtureResult.boundary.noHintingVersusNoAutoHintDistinguished `
        -or $fixtureResult.boundary.physicalLinuxReplayComplete `
        -or $fixtureResult.boundary.rendererExactOutputRecordIssuanceAllowed `
        -or $fixtureResult.boundary.certified `
        -or $fixtureResult.boundary.ready `
        -or $fixtureResult.boundary.ticket19MayClose) {
    throw 'Renderer tricky-font fixture report boundary drifted.'
}

$result = Get-Content -Raw -Encoding UTF8 -LiteralPath $report | ConvertFrom-Json
if ($result.reportVersion -ne 'renderweave-renderer-tricky-font-compatibility-gate/1.2' `
        -or $result.status -ne 'PASS_SUCCESSOR_MECHANICALLY_BUILDABLE_BUILD_PENDING' `
        -or $result.decisionStatus -ne 'SUCCESSOR_MECHANICAL_CONFIGURATION_VALID_EXACT_BUILD_PENDING' `
        -or $result.candidateId -ne 'rw-renderer-spike-linux-x86_64-v2-000003' `
        -or $result.predecessorCandidateId -ne 'rw-renderer-spike-linux-x86_64-v2-000002' `
        -or $result.checkCount -lt 150 `
        -or $result.failureCount -ne 0 `
        -or -not $result.observedCompatibility.stockOptionsReachable `
        -or $result.observedCompatibility.optionsHeaderSelfShadowing `
        -or -not $result.observedCompatibility.moduleListRepeatable `
        -or $result.observedCompatibility.moduleExpansionCountPerInclusion -ne 6 `
        -or $result.observedCompatibility.t213AdapterRequired `
        -or -not $result.observedCompatibility.currentCandidateMechanicallyBuildable `
        -or $result.observedCompatibility.runtimeBytecodeNonExecutionProven `
        -or $result.observedCompatibility.exactBuiltTargetObserved `
        -or $result.boundary.buildAttemptedByThisDecision `
        -or $result.boundary.exactBuiltTargetObserved `
        -or $result.boundary.rendererExactOutputPreissuanceReady `
        -or $result.boundary.rendererExactOutputRecordIssuanceAllowed `
        -or $result.boundary.physicalLinuxReplayComplete `
        -or $result.boundary.certified `
        -or $result.boundary.ready `
        -or $result.boundary.ticket19MayClose `
        -or $result.boundary.vendorSourceRetained `
        -or $result.boundary.fontBytesRead -ne 0 `
        -or $result.boundary.networkAttempts -ne 0 `
        -or $result.boundary.providerAttempts -ne 0) {
    throw 'Renderer tricky-font compatibility report boundary drifted.'
}

Write-Host (
    'Renderer tricky-font compatibility: {0}, checks={1}, fixture={2}/{3}, target/Certified/READY=false' -f
    $result.status, $result.checkCount, $fixtureResult.fixture.byteLength, $fixtureResult.fixture.sha256
)
