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
    throw 'Renderer evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Renderer evidence directory must already exist.'
}
$evidenceAttributes = [System.IO.File]::GetAttributes($candidateEvidenceDir)
if (($evidenceAttributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Renderer evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Renderer evidence directory escapes .sdlc/evidence.'
}

$independentReport = Join-Path $resolvedEvidenceDir 'renderer-process-independent.json'
$documentReport = Join-Path $resolvedEvidenceDir 'render-document-independent.json'
$resourceBodyReport = Join-Path $resolvedEvidenceDir 'resource-body-independent.json'
$resourceFetchTargetReport = Join-Path $resolvedEvidenceDir 'resource-fetch-target-independent.json'
$resourceFetchTransportReport = Join-Path $resolvedEvidenceDir 'resource-fetch-transport-independent.json'
$resourceMediaRawCacheReport = Join-Path $resolvedEvidenceDir 'resource-media-raw-cache-independent.json'
$imageDecodeCacheReport = Join-Path $resolvedEvidenceDir 'image-decode-cache-independent.json'
$fontPrepareCacheReport = Join-Path $resolvedEvidenceDir 'font-prepare-cache-independent.json'
$resourcePreparationPipelineReport = Join-Path $resolvedEvidenceDir 'resource-preparation-pipeline-independent.json'
$layoutPreflightReport = Join-Path $resolvedEvidenceDir 'layout-preflight-independent.json'
$definiteLayoutReport = Join-Path $resolvedEvidenceDir 'definite-layout-independent.json'
$preparedImageLayoutReport = Join-Path $resolvedEvidenceDir 'prepared-image-layout-independent.json'
$outputPngReport = Join-Path $resolvedEvidenceDir 'output-png-independent.json'
$enginePngReport = Join-Path $resolvedEvidenceDir 'engine-png-independent.json'
$enginePreparedImagePngReport = Join-Path $resolvedEvidenceDir 'engine-prepared-image-png-independent.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'renderer-process-summary.json'
foreach ($report in @(
        $independentReport,
        $documentReport,
        $resourceBodyReport,
        $resourceFetchTargetReport,
        $resourceFetchTransportReport,
        $resourceMediaRawCacheReport,
        $imageDecodeCacheReport,
        $fontPrepareCacheReport,
        $resourcePreparationPipelineReport,
        $layoutPreflightReport,
        $definiteLayoutReport,
        $preparedImageLayoutReport,
        $outputPngReport,
        $enginePngReport,
        $enginePreparedImagePngReport,
        $summaryPath)) {
    if (Test-Path -LiteralPath $report) {
        throw "Renderer process evidence already exists: $report"
    }
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
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $commandOutput = @(& $Action 2>&1)
        $commandSucceeded = $?
        $commandExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $commandOutput | ForEach-Object { Write-Host $_ }
    if (-not $commandSucceeded -or $commandExitCode -ne 0) {
        throw "$Name failed with exit code $commandExitCode."
    }
}

function Get-TestCount {
    param([Parameter(Mandatory = $true)][string[]]$ReportPaths)
    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    foreach ($reportPath in $ReportPaths) {
        if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
            throw "Expected Surefire report is absent: $reportPath"
        }
        [xml]$report = Get-Content -Raw -Encoding UTF8 -LiteralPath $reportPath
        $tests += [int]$report.testsuite.tests
        $failures += [int]$report.testsuite.failures
        $errors += [int]$report.testsuite.errors
        $skipped += [int]$report.testsuite.skipped
    }
    return [ordered]@{
        tests = $tests
        failures = $failures
        errors = $errors
        skipped = $skipped
    }
}

# This gate is offline and must not inspect or use provider credentials.
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

$rendererRoot = Join-Path $repoRoot 'renderer'
$dockerImage = 'rust:1.89-slim'
$javaTestSelector = 'RendererProcessProtocolTest,RendererProcessAdapterTest,' +
    'RendererProcessSupervisorTest,RenderEnginePortTest,RenderDocumentContractTest'

Push-Location $repoRoot
try {
    Push-Location $rendererRoot
    try {
        $cargoVersion = (& cargo.exe --version 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $cargoVersion -notmatch '^cargo 1\.89\.0 ') {
            throw "Renderer gate requires Cargo 1.89.0; observed: $cargoVersion"
        }
        $rustcVersion = (& rustc.exe --version 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $rustcVersion -notmatch '^rustc 1\.89\.0 ') {
            throw "Renderer gate requires rustc 1.89.0; observed: $rustcVersion"
        }
        $rustfmtVersion = (& rustfmt.exe --version 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) {
            throw 'Renderer gate could not resolve rustfmt.'
        }
        $clippyVersion = (& cargo.exe clippy --version 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) {
            throw 'Renderer gate could not resolve clippy.'
        }

        Invoke-Checked 'renderer-rustfmt' {
            & cargo.exe fmt --all -- --check
        }
        Invoke-Checked 'renderer-clippy' {
            & cargo.exe clippy --workspace --all-targets --locked --offline -- -D warnings
        }
        Invoke-Checked 'renderer-windows-tests' {
            & cargo.exe test --workspace --locked --offline
        }
    }
    finally {
        Pop-Location
    }

    Invoke-Checked 'renderer-java-port-tests' {
        & mvn.cmd -B -ntp -o -pl renderweave-app -am `
            "-Dtest=$javaTestSelector" `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
    }
    $java = Get-TestCount -ReportPaths @(
        (Join-Path $repoRoot 'renderweave-rendering\target\surefire-reports\TEST-cn.hbads.renderweave.rendering.internal.RenderEnginePortTest.xml'),
        (Join-Path $repoRoot 'renderweave-rendering\target\surefire-reports\TEST-cn.hbads.renderweave.rendering.internal.RenderDocumentContractTest.xml'),
        (Join-Path $repoRoot 'renderweave-app\target\surefire-reports\TEST-cn.hbads.renderweave.app.rendering.RendererProcessProtocolTest.xml'),
        (Join-Path $repoRoot 'renderweave-app\target\surefire-reports\TEST-cn.hbads.renderweave.app.rendering.RendererProcessAdapterTest.xml'),
        (Join-Path $repoRoot 'renderweave-app\target\surefire-reports\TEST-cn.hbads.renderweave.app.rendering.RendererProcessSupervisorTest.xml')
    )
    if ($java.failures -ne 0 -or $java.errors -ne 0 -or $java.skipped -ne 0) {
        throw 'Renderer Java port test report is not fully green.'
    }

    Invoke-Checked 'renderer-python-independent-replay' {
        & python.exe 'tools\verify-renderer-process-vectors.py' `
            '--vectors' 'renderer\protocol-vectors-v1.json' `
            '--manifest' 'renderer\process-manifest.json' `
            '--cargo-lock' 'renderer\Cargo.lock' `
            '--vendor' 'renderer\vendor' `
            '--report' $independentReport
    }
    if (-not (Test-Path -LiteralPath $independentReport -PathType Leaf)) {
        throw 'Renderer independent replay did not write its report.'
    }
    $independent = Get-Content -Raw -Encoding UTF8 -LiteralPath $independentReport |
        ConvertFrom-Json
    if ($independent.reportVersion -ne 'renderweave-renderer-process-independent/1.0' `
            -or $independent.engine -ne 'python-stdlib-independent' `
            -or $independent.assurance -ne 'A2' `
            -or $independent.status -ne 'PASS' `
            -or $independent.checks -ne 110 `
            -or $independent.failed -ne 0 `
            -or $independent.vectorCases -ne 7 `
            -or $independent.vendorFileCount -ne 3067 `
            -or $independent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $independent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $independent.rasterImplementation -ne 'ABSENT' `
            -or $independent.providerAttempts -ne 0) {
        throw 'Renderer independent report boundary drifted.'
    }

    Invoke-Checked 'render-document-python-independent-replay' {
        & python.exe 'tools\verify-render-document-vectors.py' `
            '--catalog' 'renderweave-rendering\src\main\resources\cn\hbads\renderweave\rendering\render-node-contract-v1.json' `
            '--vectors' 'renderer\render-document-vectors-v1.json' `
            '--all-kinds' 'renderer\render-document-all-kinds-v1.json' `
            '--protocol-vectors' 'renderer\protocol-vectors-v1.json' `
            '--report' $documentReport
    }
    if (-not (Test-Path -LiteralPath $documentReport -PathType Leaf)) {
        throw 'RenderDocument independent replay did not write its report.'
    }
    $documentIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath $documentReport |
        ConvertFrom-Json
    if ($documentIndependent.verifier -ne 'renderweave-render-document-python-independent/3' `
            -or $documentIndependent.result -ne 'PASS' `
            -or $documentIndependent.assurance -ne 'A2' `
            -or $documentIndependent.passed -ne 83 `
            -or $documentIndependent.total -ne 83 `
            -or $documentIndependent.documentCases -ne 14 `
            -or $documentIndependent.resourceCases -ne 42 `
            -or $documentIndependent.resourceAggregateCases -ne 19 `
            -or $documentIndependent.resourceLeaseCases -ne 8 `
            -or $documentIndependent.checks -ne 106 `
            -or $documentIndependent.resourceAdmission -ne 'TYPED_MANIFEST_AND_COMMAND_LEASE_PREFLIGHT_ONLY' `
            -or $documentIndependent.leaseSafetyMarginMillis -ne 5000 `
            -or $documentIndependent.resourceBytes -ne 'UNFETCHED' `
            -or $documentIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $documentIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $documentIndependent.rasterImplementation -ne 'ABSENT' `
            -or $documentIndependent.providerAttempts -ne 0) {
        throw 'RenderDocument independent report boundary drifted.'
    }

    Invoke-Checked 'resource-body-python-independent-replay' {
        & python.exe 'tools\verify-resource-body-vectors.py' `
            '--vectors' 'renderer\resource-body-vectors-v1.json' `
            '--report' $resourceBodyReport
    }
    if (-not (Test-Path -LiteralPath $resourceBodyReport -PathType Leaf)) {
        throw 'Resource body independent replay did not write its report.'
    }
    $resourceBodyIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath $resourceBodyReport |
        ConvertFrom-Json
    if ($resourceBodyIndependent.verifier -ne 'renderweave-resource-body-python-independent/1' `
            -or $resourceBodyIndependent.result -ne 'PASS' `
            -or $resourceBodyIndependent.assurance -ne 'A2' `
            -or $resourceBodyIndependent.budgetCases -ne 6 `
            -or $resourceBodyIndependent.bodyCases -ne 9 `
            -or $resourceBodyIndependent.passed -ne 15 `
            -or $resourceBodyIndependent.total -ne 15 `
            -or $resourceBodyIndependent.failed -ne 0 `
            -or $resourceBodyIndependent.checks -ne 34 `
            -or $resourceBodyIndependent.physicalFetchBytesLimit -ne 536870912 `
            -or $resourceBodyIndependent.physicalFetchBytesLimitId -ne 'assetsAndFetch.physicalFetchBytesIncludingRetries' `
            -or ($resourceBodyIndependent.integrityOrder -join '|') -ne 'PHYSICAL_FETCH_BUDGET|DECLARED_LENGTH|LOWERCASE_SHA256' `
            -or $resourceBodyIndependent.resourceInput -ne 'CALLER_SUPPLIED_CHUNKS' `
            -or $resourceBodyIndependent.resourceBytes -ne 'UNFETCHED' `
            -or $resourceBodyIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $resourceBodyIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $resourceBodyIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $resourceBodyIndependent.providerAttempts -ne 0) {
        throw 'Resource body independent report boundary drifted.'
    }

    Invoke-Checked 'resource-fetch-target-python-independent-replay' {
        & python.exe 'tools\verify-resource-fetch-target-vectors.py' `
            '--vectors' 'renderer\resource-fetch-target-vectors-v1.json' `
            '--report' $resourceFetchTargetReport
    }
    if (-not (Test-Path -LiteralPath $resourceFetchTargetReport -PathType Leaf)) {
        throw 'Resource fetch target independent replay did not write its report.'
    }
    $resourceFetchTargetIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath $resourceFetchTargetReport |
        ConvertFrom-Json
    if ($resourceFetchTargetIndependent.verifier -ne 'renderweave-resource-fetch-target-python-independent/1' `
            -or $resourceFetchTargetIndependent.result -ne 'PASS' `
            -or $resourceFetchTargetIndependent.assurance -ne 'A2' `
            -or $resourceFetchTargetIndependent.policyCases -ne 14 `
            -or $resourceFetchTargetIndependent.targetCases -ne 22 `
            -or $resourceFetchTargetIndependent.passed -ne 36 `
            -or $resourceFetchTargetIndependent.total -ne 36 `
            -or $resourceFetchTargetIndependent.failed -ne 0 `
            -or $resourceFetchTargetIndependent.checks -ne 76 `
            -or $resourceFetchTargetIndependent.engineStage -ne 'RESOURCE_PREPARATION' `
            -or $resourceFetchTargetIndependent.assetFetchPathPrefix -ne '/internal/render-assets' `
            -or $resourceFetchTargetIndependent.targetInput -ne 'TYPED_RENDER_RESOURCE' `
            -or $resourceFetchTargetIndependent.transportImplementation -ne 'UNWIRED' `
            -or $resourceFetchTargetIndependent.resourceBytes -ne 'UNFETCHED' `
            -or $resourceFetchTargetIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $resourceFetchTargetIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $resourceFetchTargetIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $resourceFetchTargetIndependent.processRasterImplementation -ne 'ABSENT' `
            -or $resourceFetchTargetIndependent.productRoute -ne 'CLOSED' `
            -or $resourceFetchTargetIndependent.providerAttempts -ne 0) {
        throw 'Resource fetch target independent report boundary drifted.'
    }

    Invoke-Checked 'resource-fetch-transport-python-independent-replay' {
        & python.exe 'tools\verify-resource-fetch-transport.py' `
            '--vectors' 'renderer\resource-fetch-transport-vectors-v1.json' `
            '--report' $resourceFetchTransportReport
    }
    if (-not (Test-Path -LiteralPath $resourceFetchTransportReport -PathType Leaf)) {
        throw 'Resource fetch transport independent replay did not write its report.'
    }
    $resourceFetchTransportIndependent =
        Get-Content -Raw -Encoding UTF8 -LiteralPath $resourceFetchTransportReport |
            ConvertFrom-Json
    if ($resourceFetchTransportIndependent.verifier -ne 'renderweave-resource-fetch-transport-python-independent/1' `
            -or $resourceFetchTransportIndependent.result -ne 'PASS' `
            -or $resourceFetchTransportIndependent.assurance -ne 'A2' `
            -or $resourceFetchTransportIndependent.egressCases -ne 9 `
            -or $resourceFetchTransportIndependent.responseCases -ne 12 `
            -or $resourceFetchTransportIndependent.scheduleCases -ne 12 `
            -or $resourceFetchTransportIndependent.passed -ne 33 `
            -or $resourceFetchTransportIndependent.total -ne 33 `
            -or $resourceFetchTransportIndependent.failed -ne 0 `
            -or $resourceFetchTransportIndependent.checks -ne 115 `
            -or $resourceFetchTransportIndependent.transportImplementation -ne 'RUSTLS_HTTPS_AUTOMATED_VERIFIED' `
            -or $resourceFetchTransportIndependent.resourceBytes -ne 'FETCHED_AND_INTEGRITY_VERIFIED' `
            -or $resourceFetchTransportIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $resourceFetchTransportIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $resourceFetchTransportIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $resourceFetchTransportIndependent.processRasterImplementation -ne 'ABSENT' `
            -or $resourceFetchTransportIndependent.productRoute -ne 'CLOSED' `
            -or $resourceFetchTransportIndependent.providerAttempts -ne 0) {
        throw 'Resource fetch transport independent report boundary drifted.'
    }

    Invoke-Checked 'resource-media-raw-cache-python-independent-replay' {
        & python.exe 'tools\verify-resource-media-raw-cache.py' `
            '--vectors' 'renderer\resource-media-raw-cache-vectors-v1.json' `
            '--repo-root' $repoRoot `
            '--report' $resourceMediaRawCacheReport
    }
    if (-not (Test-Path -LiteralPath $resourceMediaRawCacheReport -PathType Leaf)) {
        throw 'Resource media/raw-cache independent replay did not write its report.'
    }
    $resourceMediaRawCacheIndependent =
        Get-Content -Raw -Encoding UTF8 -LiteralPath $resourceMediaRawCacheReport |
            ConvertFrom-Json
    if ($resourceMediaRawCacheIndependent.verifier -ne 'renderweave-resource-media-raw-cache-python-independent/1' `
            -or $resourceMediaRawCacheIndependent.result -ne 'PASS' `
            -or $resourceMediaRawCacheIndependent.assurance -ne 'A2' `
            -or $resourceMediaRawCacheIndependent.supportedCases -ne 13 `
            -or $resourceMediaRawCacheIndependent.defensiveCases -ne 22 `
            -or $resourceMediaRawCacheIndependent.descriptorCases -ne 5 `
            -or $resourceMediaRawCacheIndependent.deferredCases -ne 7 `
            -or $resourceMediaRawCacheIndependent.cacheCases -ne 7 `
            -or $resourceMediaRawCacheIndependent.passed -ne 54 `
            -or $resourceMediaRawCacheIndependent.total -ne 54 `
            -or $resourceMediaRawCacheIndependent.failed -ne 0 `
            -or $resourceMediaRawCacheIndependent.checks -ne 239 `
            -or $resourceMediaRawCacheIndependent.vectorSha256 -ne 'sha256:33308c3ac6be7e68f29f515563a7a8e462f5fe2e6ab3b1fba40ecda0508d889f' `
            -or $resourceMediaRawCacheIndependent.assetKernelVectorSha256 -ne 'sha256:0f44fdef29d989049e77bcf3659fca4b7958b7009053c82d74c46f9f1984e4ca' `
            -or $resourceMediaRawCacheIndependent.rendererProfileIdentity -ne 'renderweave-renderer/1.0' `
            -or $resourceMediaRawCacheIndependent.requestRawCacheBytes -ne 268435456 `
            -or $resourceMediaRawCacheIndependent.requestRawCacheLimitId -ne 'assetsAndFetch.requestRawCacheBytes' `
            -or $resourceMediaRawCacheIndependent.resourceBytes -ne 'MEDIA_DESCRIPTOR_PREFLIGHT_AUTOMATED_VERIFIED' `
            -or $resourceMediaRawCacheIndependent.imageDecode -ne 'DEFERRED' `
            -or $resourceMediaRawCacheIndependent.fontFullParse -ne 'DEFERRED' `
            -or $resourceMediaRawCacheIndependent.decodedCache -ne 'ABSENT' `
            -or $resourceMediaRawCacheIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $resourceMediaRawCacheIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $resourceMediaRawCacheIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $resourceMediaRawCacheIndependent.processRasterImplementation -ne 'ABSENT' `
            -or $resourceMediaRawCacheIndependent.productRoute -ne 'CLOSED' `
            -or $resourceMediaRawCacheIndependent.providerAttempts -ne 0) {
        throw 'Resource media/raw-cache independent report boundary drifted.'
    }

    Invoke-Checked 'image-decode-cache-python-independent-replay' {
        & python.exe 'tools\verify-image-decode-cache-vectors.py' `
            '--vectors' 'renderer\image-decode-cache-vectors-v1.json' `
            '--repo-root' $repoRoot `
            '--report' $imageDecodeCacheReport
    }
    if (-not (Test-Path -LiteralPath $imageDecodeCacheReport -PathType Leaf)) {
        throw 'IMAGE decode/cache independent replay did not write its report.'
    }
    $imageDecodeCacheIndependent =
        Get-Content -Raw -Encoding UTF8 -LiteralPath $imageDecodeCacheReport |
            ConvertFrom-Json
    if ($imageDecodeCacheIndependent.verifier -ne 'renderweave-image-decode-cache-python-independent/1' `
            -or $imageDecodeCacheIndependent.result -ne 'PASS' `
            -or $imageDecodeCacheIndependent.assurance -ne 'A2' `
            -or $imageDecodeCacheIndependent.codecPixelAssurance -ne 'A1_RUST_PRIMARY_WITH_FROZEN_EXPECTED_BYTES' `
            -or $imageDecodeCacheIndependent.structuralAssurance -ne 'A2_PYTHON_STDLIB_INDEPENDENT' `
            -or $imageDecodeCacheIndependent.decodeCases -ne 14 `
            -or $imageDecodeCacheIndependent.failureCases -ne 4 `
            -or $imageDecodeCacheIndependent.orientationCases -ne 8 `
            -or $imageDecodeCacheIndependent.cacheCases -ne 7 `
            -or $imageDecodeCacheIndependent.passed -ne 33 `
            -or $imageDecodeCacheIndependent.total -ne 33 `
            -or $imageDecodeCacheIndependent.failed -ne 0 `
            -or $imageDecodeCacheIndependent.checks -ne 394 `
            -or $imageDecodeCacheIndependent.vectorSha256 -ne 'sha256:dfff93643ace7658f7e07e8b661bbe1a80af9af6aa2b1fa2138d81e329729c18' `
            -or $imageDecodeCacheIndependent.assetKernelVectorSha256 -ne 'sha256:0f44fdef29d989049e77bcf3659fca4b7958b7009053c82d74c46f9f1984e4ca' `
            -or $imageDecodeCacheIndependent.canonicalSrgbIccSha256 -ne 'sha256:2b3aa1645779a9e634744faf9b01e9102b0c9b88fd6deced7934df86b949af7e' `
            -or $imageDecodeCacheIndependent.expectedPixelCorpusSha256 -ne 'sha256:7e9f943643709136c69dfce8f0af58889f8a852eefbabe58505f6a5626dfe3b9' `
            -or $imageDecodeCacheIndependent.rendererProfileIdentity -ne 'renderweave-renderer/1.0' `
            -or $imageDecodeCacheIndependent.requestDecodedCacheBytes -ne 536870912 `
            -or $imageDecodeCacheIndependent.requestDecodedCacheLimitId -ne 'assetsAndFetch.requestDecodedCacheBytes' `
            -or $imageDecodeCacheIndependent.decoderScratchBytes -ne 134217728 `
            -or $imageDecodeCacheIndependent.decoderScratchLimitId -ne 'rendererSurfaceAndOutput.decoderScratchBytes' `
            -or $imageDecodeCacheIndependent.resourceBytes -ne 'FULL_IMAGE_DECODE_AUTOMATED_VERIFIED_UNWIRED' `
            -or $imageDecodeCacheIndependent.imageDecode -ne 'STATIC_PNG_JPEG_WEBP_STRAIGHT_RGBA8_ORIENTED' `
            -or $imageDecodeCacheIndependent.fontFullParse -ne 'DEFERRED' `
            -or $imageDecodeCacheIndependent.decodedCache -ne 'REQUEST_LOCAL_CONTENT_ADDRESSED_536870912_BYTES' `
            -or $imageDecodeCacheIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $imageDecodeCacheIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $imageDecodeCacheIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $imageDecodeCacheIndependent.processRasterImplementation -ne 'ABSENT' `
            -or $imageDecodeCacheIndependent.productRoute -ne 'CLOSED' `
            -or $imageDecodeCacheIndependent.providerAttempts -ne 0) {
        throw 'IMAGE decode/cache independent report boundary drifted.'
    }

    Invoke-Checked 'font-prepare-cache-python-independent-replay' {
        & python.exe 'tools\verify-font-prepare-cache-vectors.py' `
            '--vectors' 'renderer\font-prepare-cache-vectors-v1.json' `
            '--repo-root' $repoRoot `
            '--report' $fontPrepareCacheReport
    }
    if (-not (Test-Path -LiteralPath $fontPrepareCacheReport -PathType Leaf)) {
        throw 'FONT prepare/cache independent replay did not write its report.'
    }
    $fontPrepareCacheIndependent =
        Get-Content -Raw -Encoding UTF8 -LiteralPath $fontPrepareCacheReport |
            ConvertFrom-Json
    if ($fontPrepareCacheIndependent.verifier -ne 'renderweave-font-prepare-cache-python-independent/1' `
            -or $fontPrepareCacheIndependent.result -ne 'PASS' `
            -or $fontPrepareCacheIndependent.assurance -ne 'A2' `
            -or $fontPrepareCacheIndependent.structuralAssurance -ne 'A2_PYTHON_STDLIB_INDEPENDENT' `
            -or $fontPrepareCacheIndependent.assetCorpusAssurance -ne 'A2_EXISTING_FONTTOOLS_AND_STDLIB_GATE_REUSED' `
            -or $fontPrepareCacheIndependent.cacheBudgetAssurance -ne 'A2_PYTHON_STDLIB_INDEPENDENT' `
            -or $fontPrepareCacheIndependent.preparedCases -ne 2 `
            -or $fontPrepareCacheIndependent.failureCases -ne 3 `
            -or $fontPrepareCacheIndependent.cacheCases -ne 4 `
            -or $fontPrepareCacheIndependent.budgetCases -ne 6 `
            -or $fontPrepareCacheIndependent.passed -ne 15 `
            -or $fontPrepareCacheIndependent.total -ne 15 `
            -or $fontPrepareCacheIndependent.failed -ne 0 `
            -or $fontPrepareCacheIndependent.checks -ne 184 `
            -or $fontPrepareCacheIndependent.vectorSha256 -ne 'sha256:1e7b33cf8c02b1ef73b5e9094121e7e524360462200e1f74692410b36603598f' `
            -or $fontPrepareCacheIndependent.assetKernelVectorSha256 -ne 'sha256:0f44fdef29d989049e77bcf3659fca4b7958b7009053c82d74c46f9f1984e4ca' `
            -or $fontPrepareCacheIndependent.mutationCorpusSha256 -ne 'sha256:1c9b677d253719b053693dd94b7cb31cd362ff58d3e2cee6d69efcb107ed7db7' `
            -or $fontPrepareCacheIndependent.rendererProfileIdentity -ne 'renderweave-renderer/1.0' `
            -or $fontPrepareCacheIndependent.requestUniqueFonts -ne 32 `
            -or $fontPrepareCacheIndependent.requestUniqueFontsLimitId -ne 'layoutFontAndRaster.uniqueFonts' `
            -or $fontPrepareCacheIndependent.fontTablesPerContent -ne 256 `
            -or $fontPrepareCacheIndependent.fontTablesPerContentLimitId -ne 'layoutFontAndRaster.tablesPerFont' `
            -or $fontPrepareCacheIndependent.requestFontTables -ne 4096 `
            -or $fontPrepareCacheIndependent.requestFontTablesLimitId -ne 'layoutFontAndRaster.fontTablesTotal' `
            -or $fontPrepareCacheIndependent.resourceBytes -ne 'FULL_FONT_PARSE_AUTOMATED_VERIFIED_UNWIRED' `
            -or $fontPrepareCacheIndependent.fontPreparation -ne 'ASSET_APPROVED_TTF_GLYF_CFF_CMAP_DESCRIPTOR_FACTS' `
            -or $fontPrepareCacheIndependent.fontShaping -ne 'UNWIRED' `
            -or $fontPrepareCacheIndependent.glyphConsumer -ne 'UNWIRED' `
            -or $fontPrepareCacheIndependent.nativeFontStack -ne 'BUILD_NOT_AUTHORIZED' `
            -or $fontPrepareCacheIndependent.preparedCache -ne 'REQUEST_LOCAL_CONTENT_ADDRESSED_32_FONTS_4096_TABLES' `
            -or $fontPrepareCacheIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $fontPrepareCacheIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $fontPrepareCacheIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $fontPrepareCacheIndependent.processRasterImplementation -ne 'ABSENT' `
            -or $fontPrepareCacheIndependent.productRoute -ne 'CLOSED' `
            -or $fontPrepareCacheIndependent.providerAttempts -ne 0) {
        throw 'FONT prepare/cache independent report boundary drifted.'
    }

    Invoke-Checked 'resource-preparation-pipeline-python-independent-replay' {
        & python.exe 'tools\verify-resource-preparation-pipeline.py' `
            '--vectors' 'renderer\resource-preparation-pipeline-vectors-v1.json' `
            '--repo-root' $repoRoot `
            '--report' $resourcePreparationPipelineReport
    }
    if (-not (Test-Path -LiteralPath $resourcePreparationPipelineReport -PathType Leaf)) {
        throw 'Resource preparation pipeline independent replay did not write its report.'
    }
    $resourcePreparationPipelineIndependent =
        Get-Content -Raw -Encoding UTF8 -LiteralPath $resourcePreparationPipelineReport |
            ConvertFrom-Json
    if ($resourcePreparationPipelineIndependent.verifier -ne 'renderweave-resource-preparation-pipeline-python-independent/1' `
            -or $resourcePreparationPipelineIndependent.result -ne 'PASS' `
            -or $resourcePreparationPipelineIndependent.assurance -ne 'A2' `
            -or $resourcePreparationPipelineIndependent.pipelineAssurance -ne 'A2_PYTHON_STDLIB_ORDER_CACHE_PROBLEM_STATE_MODEL' `
            -or $resourcePreparationPipelineIndependent.codecAssurance -ne 'A2_EXISTING_IMAGE_FONT_INDEPENDENT_VECTORS_REUSED' `
            -or $resourcePreparationPipelineIndependent.successCases -ne 2 `
            -or $resourcePreparationPipelineIndependent.preparationFailureCases -ne 2 `
            -or $resourcePreparationPipelineIndependent.fetchFailureCases -ne 1 `
            -or $resourcePreparationPipelineIndependent.controlCases -ne 2 `
            -or $resourcePreparationPipelineIndependent.passed -ne 7 `
            -or $resourcePreparationPipelineIndependent.total -ne 7 `
            -or $resourcePreparationPipelineIndependent.failed -ne 0 `
            -or $resourcePreparationPipelineIndependent.checks -ne 102 `
            -or $resourcePreparationPipelineIndependent.vectorSha256 -ne 'sha256:4943ac9da9e44aa08607d8ddee7f4c677dcf0d9ae84f1a1b6831f2c94782ccb7' `
            -or $resourcePreparationPipelineIndependent.assetKernelVectorSha256 -ne 'sha256:0f44fdef29d989049e77bcf3659fca4b7958b7009053c82d74c46f9f1984e4ca' `
            -or $resourcePreparationPipelineIndependent.imageDecodeVectorSha256 -ne 'sha256:dfff93643ace7658f7e07e8b661bbe1a80af9af6aa2b1fa2138d81e329729c18' `
            -or $resourcePreparationPipelineIndependent.fontPrepareVectorSha256 -ne 'sha256:1e7b33cf8c02b1ef73b5e9094121e7e524360462200e1f74692410b36603598f' `
            -or $resourcePreparationPipelineIndependent.mutationCorpusSha256 -ne 'sha256:99ec8636a6fe4826766695615a850f232736a945491e8c2ffe9a1d8dfc752e9c' `
            -or $resourcePreparationPipelineIndependent.rendererProfileIdentity -ne 'renderweave-renderer/1.0' `
            -or $resourcePreparationPipelineIndependent.resourcePreparationPipeline -ne 'MANIFEST_ORDER_FETCH_RAW_IMAGE_FONT_AUTOMATED_VERIFIED' `
            -or $resourcePreparationPipelineIndependent.resourceManifest -ne 'IMMUTABLE_COMPLETE_ONLY' `
            -or $resourcePreparationPipelineIndependent.fontShaping -ne 'UNWIRED' `
            -or $resourcePreparationPipelineIndependent.glyphConsumer -ne 'UNWIRED' `
            -or $resourcePreparationPipelineIndependent.nativeFontStack -ne 'BUILD_NOT_AUTHORIZED' `
            -or $resourcePreparationPipelineIndependent.sceneConsumer -ne 'UNWIRED' `
            -or $resourcePreparationPipelineIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $resourcePreparationPipelineIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $resourcePreparationPipelineIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $resourcePreparationPipelineIndependent.processRasterImplementation -ne 'ABSENT' `
            -or $resourcePreparationPipelineIndependent.productRoute -ne 'CLOSED' `
            -or $resourcePreparationPipelineIndependent.providerAttempts -ne 0) {
        throw 'Resource preparation pipeline independent report boundary drifted.'
    }

    Invoke-Checked 'layout-preflight-python-independent-replay' {
        & python.exe 'tools\verify-layout-preflight-vectors.py' `
            '--vectors' 'renderer\layout-preflight-vectors-v1.json' `
            '--fixtures' 'renderer\layout-preflight-fixtures-v1.json' `
            '--all-kinds' 'renderer\render-document-all-kinds-v1.json' `
            '--report' $layoutPreflightReport
    }
    if (-not (Test-Path -LiteralPath $layoutPreflightReport -PathType Leaf)) {
        throw 'Layout preflight independent replay did not write its report.'
    }
    $layoutPreflightIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath $layoutPreflightReport |
        ConvertFrom-Json
    if ($layoutPreflightIndependent.verifier -ne 'renderweave-layout-preflight-python-independent/1' `
            -or $layoutPreflightIndependent.result -ne 'PASS' `
            -or $layoutPreflightIndependent.assurance -ne 'A2' `
            -or $layoutPreflightIndependent.positiveCases -ne 7 `
            -or $layoutPreflightIndependent.negativeCases -ne 25 `
            -or $layoutPreflightIndependent.passed -ne 32 `
            -or $layoutPreflightIndependent.total -ne 32 `
            -or $layoutPreflightIndependent.failed -ne 0 `
            -or $layoutPreflightIndependent.checks -ne 77 `
            -or $layoutPreflightIndependent.layoutProfile -ne 'renderweave-layout/1.0' `
            -or $layoutPreflightIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $layoutPreflightIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $layoutPreflightIndependent.layoutImplementation -ne 'STATIC_PREFLIGHT_ONLY' `
            -or $layoutPreflightIndependent.rasterImplementation -ne 'ABSENT' `
            -or $layoutPreflightIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $layoutPreflightIndependent.providerAttempts -ne 0) {
        throw 'Layout preflight independent report boundary drifted.'
    }

    Invoke-Checked 'definite-layout-python-independent-replay' {
        & python.exe 'tools\verify-definite-layout-vectors.py' `
            '--vectors' 'renderer\definite-layout-vectors-v1.json' `
            '--fixtures' 'renderer\definite-layout-fixtures-v1.json' `
            '--layout-preflight-fixtures' 'renderer\layout-preflight-fixtures-v1.json' `
            '--all-kinds' 'renderer\render-document-all-kinds-v1.json' `
            '--report' $definiteLayoutReport
    }
    if (-not (Test-Path -LiteralPath $definiteLayoutReport -PathType Leaf)) {
        throw 'Definite layout independent replay did not write its report.'
    }
    $definiteLayoutIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath $definiteLayoutReport |
        ConvertFrom-Json
    if ($definiteLayoutIndependent.verifier -ne 'renderweave-definite-layout-python-independent/60' `
            -or $definiteLayoutIndependent.result -ne 'PASS' `
            -or $definiteLayoutIndependent.assurance -ne 'A2' `
            -or $definiteLayoutIndependent.laidOutCases -ne 278 `
            -or $definiteLayoutIndependent.unsupportedCases -ne 10 `
            -or $definiteLayoutIndependent.passed -ne 288 `
            -or $definiteLayoutIndependent.total -ne 288 `
            -or $definiteLayoutIndependent.failed -ne 0 `
            -or $definiteLayoutIndependent.checks -ne 868 `
            -or $definiteLayoutIndependent.layoutProfile -ne 'renderweave-layout/1.0' `
            -or $definiteLayoutIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $definiteLayoutIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $definiteLayoutIndependent.layoutImplementation -ne 'RESOURCE_FREE_DEFINITE_ABSOLUTE_STACK_BOUNDED_ITERATIVE_MAIN_FILL_MIN_MAX_WATER_FILLING_AND_FIXED_SINGLE_FRACTION_INDEPENDENT_MULTI_AUTO_GRID_MULTI_AUTO_SPAN_STABLE_DEFICIT_GRID_DEFINITE_MULTI_FRACTION_LAST_REMAINDER_GRID_EMPTY_CONTAINER_STACK_HUG_GRID_AUTO_HUG_CONTRIBUTION_GRID_HUG_EXACT_QUARTER_TURN_AFFINE_FRAME_GROUP_HUG_FIXED_OPPOSITE_AXIS_CROSS_FILL_DEFINITE_ABSOLUTE_PARENT_OFFER_DEFINITE_STACK_CROSS_OUTER_OFFER_STACK_MAIN_FILL_CROSS_HUG_REMEASURE_NESTED_STACK_MAIN_OFFER_PROPAGATION_COLUMNS_FIRST_GRID_CELL_OUTER_OFFER_STACK_MAIN_OFFER_COLUMNS_FIRST_GRID_CROSS_HUG_ABSOLUTE_PARENT_OFFER_COLUMNS_FIRST_GRID_CROSS_HUG_GRID_CELL_OFFER_COLUMNS_FIRST_NESTED_GRID_CROSS_HUG_GRID_CELL_OFFER_STACK_MAIN_FIRST_CROSS_HUG_DIRECTION_CHANGING_STACK_CROSS_OFFER_MAIN_HUG_NESTED_STACK_RESOLVED_OPPOSITE_OFFER_RECURSION_COLUMNS_FIRST_GRID_TERMINAL_NORMALIZATION_BOX_KERNEL_DEFINITE_COMPOSITION_VIEWPORT_SOURCE_TRIM_CONTAIN_CENTER_MAPPING_BOX_KERNEL' `
            -or $definiteLayoutIndependent.worldTransformImplementation -ne 'ABSENT' `
            -or $definiteLayoutIndependent.sceneImplementation -ne 'ABSENT' `
            -or $definiteLayoutIndependent.rasterImplementation -ne 'ABSENT' `
            -or $definiteLayoutIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $definiteLayoutIndependent.providerAttempts -ne 0) {
        throw 'Definite layout independent report boundary drifted.'
    }

    Invoke-Checked 'prepared-image-layout-python-independent-replay' {
        & python.exe 'tools\verify-prepared-image-layout-vectors.py' `
            '--vectors' 'renderer\prepared-image-layout-vectors-v1.json' `
            '--report' $preparedImageLayoutReport
    }
    if (-not (Test-Path -LiteralPath $preparedImageLayoutReport -PathType Leaf)) {
        throw 'Prepared IMAGE layout independent replay did not write its report.'
    }
    $preparedImageLayoutIndependent =
        Get-Content -Raw -Encoding UTF8 -LiteralPath $preparedImageLayoutReport |
            ConvertFrom-Json
    if ($preparedImageLayoutIndependent.verifier -ne 'renderweave-prepared-image-layout-python-independent/1' `
            -or $preparedImageLayoutIndependent.result -ne 'PASS' `
            -or $preparedImageLayoutIndependent.assurance -ne 'A2' `
            -or $preparedImageLayoutIndependent.successCases -ne 10 `
            -or $preparedImageLayoutIndependent.negativeCases -ne 2 `
            -or $preparedImageLayoutIndependent.passed -ne 12 `
            -or $preparedImageLayoutIndependent.total -ne 12 `
            -or $preparedImageLayoutIndependent.failed -ne 0 `
            -or $preparedImageLayoutIndependent.checks -ne 81 `
            -or $preparedImageLayoutIndependent.vectorSha256 -ne '275579debd1ba894a64836258da402ea0e974046895ae985642be832cf430b14' `
            -or $preparedImageLayoutIndependent.layoutProfile -ne 'renderweave-layout/1.0' `
            -or $preparedImageLayoutIndependent.resourcePreparationProfile -ne 'renderweave-renderer/1.0' `
            -or $preparedImageLayoutIndependent.intrinsicSource -ne 'EXACT_BYTES_ORIENTATION_NORMALIZED_LOGICAL_PIXELS' `
            -or $preparedImageLayoutIndependent.layoutImplementation -ne 'PREPARED_IMAGE_FIXED_FILL_SINGLE_AXIS_HUG_LOGICAL_RATIO_ABSOLUTE_STACK_GRID_CONTAINER_AUTOMATED_VERIFIED_UNWIRED' `
            -or $preparedImageLayoutIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $preparedImageLayoutIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $preparedImageLayoutIndependent.sceneImplementation -ne 'ABSENT' `
            -or $preparedImageLayoutIndependent.rasterImplementation -ne 'ABSENT' `
            -or $preparedImageLayoutIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $preparedImageLayoutIndependent.productRoute -ne 'CLOSED' `
            -or $preparedImageLayoutIndependent.providerAttempts -ne 0) {
        throw 'Prepared IMAGE layout independent report boundary drifted.'
    }

    Invoke-Checked 'output-png-python-independent-replay' {
        & python.exe 'tools\verify-output-png-vectors.py' `
            '--vectors' 'renderer\output-png-vectors-v1.json' `
            '--report' $outputPngReport
    }
    if (-not (Test-Path -LiteralPath $outputPngReport -PathType Leaf)) {
        throw 'Output PNG independent replay did not write its report.'
    }
    $outputPngIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath $outputPngReport |
        ConvertFrom-Json
    if ($outputPngIndependent.verifier -ne 'renderweave-output-png-python-independent/1' `
            -or $outputPngIndependent.result -ne 'PASS' `
            -or $outputPngIndependent.assurance -ne 'A2' `
            -or $outputPngIndependent.surfaceCases -ne 10 `
            -or $outputPngIndependent.pngCases -ne 6 `
            -or $outputPngIndependent.passed -ne 16 `
            -or $outputPngIndependent.total -ne 16 `
            -or $outputPngIndependent.failed -ne 0 `
            -or $outputPngIndependent.checks -ne 90 `
            -or $outputPngIndependent.outputProfile -ne 'renderweave-output-png/1.0' `
            -or $outputPngIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $outputPngIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $outputPngIndependent.rasterImplementation -ne 'ABSENT' `
            -or $outputPngIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $outputPngIndependent.physicalHostCertification -ne $false `
            -or $outputPngIndependent.providerAttempts -ne 0) {
        throw 'Output PNG independent report boundary drifted.'
    }

    Invoke-Checked 'engine-png-python-independent-replay' {
        & python.exe 'tools\verify-engine-png-vectors.py' `
            '--vectors' 'renderer\engine-png-vectors-v1.json' `
            '--report' $enginePngReport
    }
    if (-not (Test-Path -LiteralPath $enginePngReport -PathType Leaf)) {
        throw 'Engine PNG independent replay did not write its report.'
    }
    $enginePngIndependent = Get-Content -Raw -Encoding UTF8 -LiteralPath $enginePngReport |
        ConvertFrom-Json
    if ($enginePngIndependent.verifier -ne 'renderweave-engine-png-python-independent/1' `
            -or $enginePngIndependent.result -ne 'PASS' `
            -or $enginePngIndependent.assurance -ne 'A2' `
            -or $enginePngIndependent.renderedCases -ne 27 `
            -or $enginePngIndependent.unsupportedCases -ne 11 `
            -or $enginePngIndependent.passed -ne 38 `
            -or $enginePngIndependent.total -ne 38 `
            -or $enginePngIndependent.failed -ne 0 `
            -or $enginePngIndependent.checks -ne 118 `
            -or $enginePngIndependent.vectorSha256 -ne 'sha256:55b76d93490c3ed8c01b3c81084781dea3d0856af81a8d495d92017ed28163e1' `
            -or $enginePngIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $enginePngIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $enginePngIndependent.enginePngKernel -ne 'PREORDER_DEFINITE_IDENTITY_GROUP_FRAME_STACK_GRID_COMPOSITION_VIEWPORT_SOURCE_CANVAS_BACKGROUND_CONTAIN_CENTER_HOST_SOURCE_HARD_CLIP_RECT_PIXEL_ALIGNED_SOLID_ALPHA_PREMULTIPLIED_SOURCE_OVER_SUBTREE_OPACITY_ROUND_HALF_UP_ISOLATION_RECTANGULAR_CLIP_VISIBILITY_ZERO_OPACITY_SUPPRESSION_PNG_KERNEL_PROFILE_GATED' `
            -or $enginePngIndependent.processRasterImplementation -ne 'ABSENT' `
            -or $enginePngIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $enginePngIndependent.productRoute -ne 'CLOSED' `
            -or $enginePngIndependent.providerAttempts -ne 0) {
        throw 'Engine PNG independent report boundary drifted.'
    }

    Invoke-Checked 'engine-prepared-image-png-python-independent-replay' {
        & python.exe 'tools\verify-engine-prepared-image-png-vectors.py' `
            '--vectors' 'renderer\engine-prepared-image-png-vectors-v1.json' `
            '--report' $enginePreparedImagePngReport
    }
    if (-not (Test-Path -LiteralPath $enginePreparedImagePngReport -PathType Leaf)) {
        throw 'Prepared IMAGE Engine PNG independent replay did not write its report.'
    }
    $enginePreparedImagePngIndependent =
        Get-Content -Raw -Encoding UTF8 -LiteralPath $enginePreparedImagePngReport |
            ConvertFrom-Json
    if ($enginePreparedImagePngIndependent.verifier -ne 'renderweave-engine-prepared-image-png-python-independent/4' `
            -or $enginePreparedImagePngIndependent.result -ne 'PASS' `
            -or $enginePreparedImagePngIndependent.assurance -ne 'A2' `
            -or $enginePreparedImagePngIndependent.renderedCases -ne 31 `
            -or $enginePreparedImagePngIndependent.unsupportedCases -ne 2 `
            -or $enginePreparedImagePngIndependent.passed -ne 33 `
            -or $enginePreparedImagePngIndependent.total -ne 33 `
            -or $enginePreparedImagePngIndependent.failed -ne 0 `
            -or $enginePreparedImagePngIndependent.checks -ne 195 `
            -or $enginePreparedImagePngIndependent.vectorSha256 -ne '0165159dba1ad90c75faaef7e1e5d7254c8adff6e1c0e388fbea44d715148295' `
            -or $enginePreparedImagePngIndependent.layoutProfile -ne 'renderweave-layout/1.0' `
            -or $enginePreparedImagePngIndependent.resourcePreparationProfile -ne 'renderweave-renderer/1.0' `
            -or $enginePreparedImagePngIndependent.imagePixels -ne 'EXACT_ORIENTATION_NORMALIZED_STRAIGHT_RGBA8' `
            -or $enginePreparedImagePngIndependent.degenerateMapping -ne 'SOURCE_AND_INTEGER_DEVICE_BOX_EXACT_1_TO_1_CENTERED_UNIT_QUARTER_TURN_NO_RESAMPLE' `
            -or $enginePreparedImagePngIndependent.samplingMapping -ne 'INTEGER_DEVICE_BOX_HALF_INTEGER_CENTER_INVERSE_EDGE_COORDINATE_CONTAIN_COVER_FILL' `
            -or $enginePreparedImagePngIndependent.nearestTieRule -ne 'EXACT_EQUAL_DISTANCE_TO_LOWER_SOURCE_INDEX_EDGE_CLAMP' `
            -or $enginePreparedImagePngIndependent.linearArithmetic -ne 'SOURCE_PREMULTIPLY_RGBA8_EXACT_RATIONAL_BILINEAR_SINGLE_ROUND_HALF_UP_EDGE_CLAMP' `
            -or $enginePreparedImagePngIndependent.alphaArithmetic -ne 'STRAIGHT_TO_PREMULTIPLIED_MUL255_SOURCE_OVER_AUTHORED_ORDER_SUBTREE_OPACITY_ROUND_HALF_UP_255_SINGLE_FINAL_UNPREMULTIPLY' `
            -or $enginePreparedImagePngIndependent.enginePreparedImageKernel -ne 'PREPARED_IMAGE_INTEGER_BOX_CONTAIN_COVER_FILL_NEAREST_LINEAR_EXACT_RATIONAL_PREMULTIPLIED_SOURCE_OVER_CENTERED_UNIT_QUARTER_TURN_SUBTREE_OPACITY_EXACT_PNG_AUTOMATED_VERIFIED_PROFILE_GATED' `
            -or $enginePreparedImagePngIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $enginePreparedImagePngIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $enginePreparedImagePngIndependent.processRasterImplementation -ne 'ABSENT' `
            -or $enginePreparedImagePngIndependent.daemonOutputPath -ne 'UNWIRED' `
            -or $enginePreparedImagePngIndependent.productRoute -ne 'CLOSED' `
            -or $enginePreparedImagePngIndependent.providerAttempts -ne 0) {
        throw 'Prepared IMAGE Engine PNG independent report boundary drifted.'
    }

    $dockerImageId = (& docker.exe image inspect $dockerImage --format '{{.Id}}' 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $dockerImageId -notmatch '^sha256:[0-9a-f]{64}$') {
        throw "Pinned renderer Linux image is not present locally: $dockerImage"
    }
    $dockerMount = "type=bind,source=$repoRoot,target=/workspace,readonly"
    $dockerRustcVersion = (& docker.exe run --rm --pull never --network none `
            --mount $dockerMount `
            --env RUSTUP_TOOLCHAIN=1.89.0-x86_64-unknown-linux-gnu `
            --workdir /workspace/renderer $dockerImage `
            rustc --version 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $dockerRustcVersion -notmatch '^rustc 1\.89\.0 ') {
        throw "Renderer Linux replay requires rustc 1.89.0; observed: $dockerRustcVersion"
    }
    Write-Host "==> renderer-linux-uds-tests"
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $linuxOutput = @(& docker.exe run --rm --pull never --network none `
                --mount $dockerMount `
                --env CARGO_NET_OFFLINE=true `
                --env CARGO_TARGET_DIR=/tmp/renderweave-target `
                --env RUSTUP_TOOLCHAIN=1.89.0-x86_64-unknown-linux-gnu `
                --workdir /workspace/renderer $dockerImage `
                cargo test --workspace --locked --offline 2>&1)
        $linuxExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $linuxOutput | ForEach-Object { Write-Host $_ }
    $linuxText = $linuxOutput -join "`n"
    if ($linuxExitCode -ne 0) {
        throw "Renderer Linux UDS tests failed with exit code $linuxExitCode."
    }
    if ($linuxText -notmatch 'linux_uds_round_trip_handshake_command_and_replay \.\.\. ok') {
        throw 'Renderer Linux replay did not execute the real UDS round trip test.'
    }

    $summary = [ordered]@{
        gateVersion = 'renderweave-renderer-process-gate/2.7'
        status = 'PASS'
        processContractVersion = 'renderweave-renderer-process/1.0'
        java = $java
        rustWindows = [ordered]@{
            cargoVersion = $cargoVersion
            rustcVersion = $rustcVersion
            rustfmtVersion = $rustfmtVersion
            clippyVersion = $clippyVersion
            workspaceTestsPassed = $true
        }
        rustLinuxDocker = [ordered]@{
            image = $dockerImage
            imageId = $dockerImageId
            rustcVersion = $dockerRustcVersion
            networkMode = 'none'
            sourceMountReadOnly = $true
            actualUnixDomainSocketRoundTrip = $true
            workspaceTestsPassed = $true
            physicalHostCertification = $false
        }
        independent = [ordered]@{
            assurance = $independent.assurance
            checks = $independent.checks
            vectorCases = $independent.vectorCases
            vectorSha256 = $independent.vectorSha256
            manifestSha256 = $independent.manifestSha256
            cargoLockSha256 = $independent.cargoLockSha256
            vendorTreeSha256 = $independent.vendorTreeSha256
            vendorFileCount = $independent.vendorFileCount
        }
        renderDocumentIndependent = [ordered]@{
            verifier = $documentIndependent.verifier
            assurance = $documentIndependent.assurance
            cases = $documentIndependent.total
            documentCases = $documentIndependent.documentCases
            resourceCases = $documentIndependent.resourceCases
            resourceAggregateCases = $documentIndependent.resourceAggregateCases
            resourceLeaseCases = $documentIndependent.resourceLeaseCases
            checks = $documentIndependent.checks
            catalogSha256 = $documentIndependent.catalogSha256
            vectorsSha256 = $documentIndependent.vectorsSha256
            allKindsCanonicalSha256 = $documentIndependent.allKindsCanonicalSha256
            resourceAdmission = $documentIndependent.resourceAdmission
            leaseSafetyMarginMillis = $documentIndependent.leaseSafetyMarginMillis
            resourceBytes = $documentIndependent.resourceBytes
        }
        resourceBodyIndependent = [ordered]@{
            verifier = $resourceBodyIndependent.verifier
            assurance = $resourceBodyIndependent.assurance
            budgetCases = $resourceBodyIndependent.budgetCases
            bodyCases = $resourceBodyIndependent.bodyCases
            checks = $resourceBodyIndependent.checks
            vectorSha256 = $resourceBodyIndependent.vectorSha256
            physicalFetchBytesLimit = $resourceBodyIndependent.physicalFetchBytesLimit
            physicalFetchBytesLimitId = $resourceBodyIndependent.physicalFetchBytesLimitId
            integrityOrder = $resourceBodyIndependent.integrityOrder
            resourceInput = $resourceBodyIndependent.resourceInput
            resourceBytes = $resourceBodyIndependent.resourceBytes
        }
        resourceFetchTargetIndependent = [ordered]@{
            verifier = $resourceFetchTargetIndependent.verifier
            assurance = $resourceFetchTargetIndependent.assurance
            policyCases = $resourceFetchTargetIndependent.policyCases
            targetCases = $resourceFetchTargetIndependent.targetCases
            checks = $resourceFetchTargetIndependent.checks
            vectorSha256 = $resourceFetchTargetIndependent.vectorSha256
            engineStage = $resourceFetchTargetIndependent.engineStage
            assetFetchPathPrefix = $resourceFetchTargetIndependent.assetFetchPathPrefix
            targetInput = $resourceFetchTargetIndependent.targetInput
            transportImplementation = $resourceFetchTargetIndependent.transportImplementation
            resourceBytes = $resourceFetchTargetIndependent.resourceBytes
        }
        resourceFetchTransportIndependent = [ordered]@{
            verifier = $resourceFetchTransportIndependent.verifier
            assurance = $resourceFetchTransportIndependent.assurance
            egressCases = $resourceFetchTransportIndependent.egressCases
            responseCases = $resourceFetchTransportIndependent.responseCases
            scheduleCases = $resourceFetchTransportIndependent.scheduleCases
            checks = $resourceFetchTransportIndependent.checks
            vectorSha256 = $resourceFetchTransportIndependent.vectorSha256
            transportImplementation = $resourceFetchTransportIndependent.transportImplementation
            resourceBytes = $resourceFetchTransportIndependent.resourceBytes
        }
        resourceMediaRawCacheIndependent = [ordered]@{
            verifier = $resourceMediaRawCacheIndependent.verifier
            assurance = $resourceMediaRawCacheIndependent.assurance
            supportedCases = $resourceMediaRawCacheIndependent.supportedCases
            defensiveCases = $resourceMediaRawCacheIndependent.defensiveCases
            descriptorCases = $resourceMediaRawCacheIndependent.descriptorCases
            deferredCases = $resourceMediaRawCacheIndependent.deferredCases
            cacheCases = $resourceMediaRawCacheIndependent.cacheCases
            checks = $resourceMediaRawCacheIndependent.checks
            vectorSha256 = $resourceMediaRawCacheIndependent.vectorSha256
            assetKernelVectorSha256 = $resourceMediaRawCacheIndependent.assetKernelVectorSha256
            rendererProfileIdentity = $resourceMediaRawCacheIndependent.rendererProfileIdentity
            requestRawCacheBytes = $resourceMediaRawCacheIndependent.requestRawCacheBytes
            requestRawCacheLimitId = $resourceMediaRawCacheIndependent.requestRawCacheLimitId
            resourceBytes = $resourceMediaRawCacheIndependent.resourceBytes
        }
        imageDecodeCacheIndependent = [ordered]@{
            verifier = $imageDecodeCacheIndependent.verifier
            assurance = $imageDecodeCacheIndependent.assurance
            codecPixelAssurance = $imageDecodeCacheIndependent.codecPixelAssurance
            structuralAssurance = $imageDecodeCacheIndependent.structuralAssurance
            decodeCases = $imageDecodeCacheIndependent.decodeCases
            failureCases = $imageDecodeCacheIndependent.failureCases
            orientationCases = $imageDecodeCacheIndependent.orientationCases
            cacheCases = $imageDecodeCacheIndependent.cacheCases
            checks = $imageDecodeCacheIndependent.checks
            vectorSha256 = $imageDecodeCacheIndependent.vectorSha256
            assetKernelVectorSha256 = $imageDecodeCacheIndependent.assetKernelVectorSha256
            canonicalSrgbIccSha256 = $imageDecodeCacheIndependent.canonicalSrgbIccSha256
            expectedPixelCorpusSha256 = $imageDecodeCacheIndependent.expectedPixelCorpusSha256
            rendererProfileIdentity = $imageDecodeCacheIndependent.rendererProfileIdentity
            requestDecodedCacheBytes = $imageDecodeCacheIndependent.requestDecodedCacheBytes
            requestDecodedCacheLimitId = $imageDecodeCacheIndependent.requestDecodedCacheLimitId
            decoderScratchBytes = $imageDecodeCacheIndependent.decoderScratchBytes
            decoderScratchLimitId = $imageDecodeCacheIndependent.decoderScratchLimitId
            resourceBytes = $imageDecodeCacheIndependent.resourceBytes
            imageDecode = $imageDecodeCacheIndependent.imageDecode
            decodedCache = $imageDecodeCacheIndependent.decodedCache
        }
        fontPrepareCacheIndependent = [ordered]@{
            verifier = $fontPrepareCacheIndependent.verifier
            assurance = $fontPrepareCacheIndependent.assurance
            structuralAssurance = $fontPrepareCacheIndependent.structuralAssurance
            assetCorpusAssurance = $fontPrepareCacheIndependent.assetCorpusAssurance
            cacheBudgetAssurance = $fontPrepareCacheIndependent.cacheBudgetAssurance
            preparedCases = $fontPrepareCacheIndependent.preparedCases
            failureCases = $fontPrepareCacheIndependent.failureCases
            cacheCases = $fontPrepareCacheIndependent.cacheCases
            budgetCases = $fontPrepareCacheIndependent.budgetCases
            checks = $fontPrepareCacheIndependent.checks
            vectorSha256 = $fontPrepareCacheIndependent.vectorSha256
            assetKernelVectorSha256 = $fontPrepareCacheIndependent.assetKernelVectorSha256
            mutationCorpusSha256 = $fontPrepareCacheIndependent.mutationCorpusSha256
            rendererProfileIdentity = $fontPrepareCacheIndependent.rendererProfileIdentity
            requestUniqueFonts = $fontPrepareCacheIndependent.requestUniqueFonts
            requestUniqueFontsLimitId = $fontPrepareCacheIndependent.requestUniqueFontsLimitId
            fontTablesPerContent = $fontPrepareCacheIndependent.fontTablesPerContent
            fontTablesPerContentLimitId = $fontPrepareCacheIndependent.fontTablesPerContentLimitId
            requestFontTables = $fontPrepareCacheIndependent.requestFontTables
            requestFontTablesLimitId = $fontPrepareCacheIndependent.requestFontTablesLimitId
            resourceBytes = $fontPrepareCacheIndependent.resourceBytes
            fontPreparation = $fontPrepareCacheIndependent.fontPreparation
            fontShaping = $fontPrepareCacheIndependent.fontShaping
            preparedCache = $fontPrepareCacheIndependent.preparedCache
        }
        resourcePreparationPipelineIndependent = [ordered]@{
            verifier = $resourcePreparationPipelineIndependent.verifier
            assurance = $resourcePreparationPipelineIndependent.assurance
            pipelineAssurance = $resourcePreparationPipelineIndependent.pipelineAssurance
            codecAssurance = $resourcePreparationPipelineIndependent.codecAssurance
            successCases = $resourcePreparationPipelineIndependent.successCases
            preparationFailureCases = $resourcePreparationPipelineIndependent.preparationFailureCases
            fetchFailureCases = $resourcePreparationPipelineIndependent.fetchFailureCases
            controlCases = $resourcePreparationPipelineIndependent.controlCases
            checks = $resourcePreparationPipelineIndependent.checks
            vectorSha256 = $resourcePreparationPipelineIndependent.vectorSha256
            assetKernelVectorSha256 = $resourcePreparationPipelineIndependent.assetKernelVectorSha256
            imageDecodeVectorSha256 = $resourcePreparationPipelineIndependent.imageDecodeVectorSha256
            fontPrepareVectorSha256 = $resourcePreparationPipelineIndependent.fontPrepareVectorSha256
            mutationCorpusSha256 = $resourcePreparationPipelineIndependent.mutationCorpusSha256
            rendererProfileIdentity = $resourcePreparationPipelineIndependent.rendererProfileIdentity
            resourcePreparationPipeline = $resourcePreparationPipelineIndependent.resourcePreparationPipeline
            resourceManifest = $resourcePreparationPipelineIndependent.resourceManifest
        }
        layoutPreflightIndependent = [ordered]@{
            verifier = $layoutPreflightIndependent.verifier
            assurance = $layoutPreflightIndependent.assurance
            positiveCases = $layoutPreflightIndependent.positiveCases
            negativeCases = $layoutPreflightIndependent.negativeCases
            checks = $layoutPreflightIndependent.checks
            vectorSha256 = $layoutPreflightIndependent.vectorSha256
            fixturesSha256 = $layoutPreflightIndependent.fixturesSha256
            layoutProfile = $layoutPreflightIndependent.layoutProfile
        }
        definiteLayoutIndependent = [ordered]@{
            verifier = $definiteLayoutIndependent.verifier
            assurance = $definiteLayoutIndependent.assurance
            laidOutCases = $definiteLayoutIndependent.laidOutCases
            unsupportedCases = $definiteLayoutIndependent.unsupportedCases
            checks = $definiteLayoutIndependent.checks
            vectorSha256 = $definiteLayoutIndependent.vectorSha256
            fixturesSha256 = $definiteLayoutIndependent.fixturesSha256
            layoutProfile = $definiteLayoutIndependent.layoutProfile
        }
        preparedImageLayoutIndependent = [ordered]@{
            verifier = $preparedImageLayoutIndependent.verifier
            assurance = $preparedImageLayoutIndependent.assurance
            successCases = $preparedImageLayoutIndependent.successCases
            negativeCases = $preparedImageLayoutIndependent.negativeCases
            checks = $preparedImageLayoutIndependent.checks
            vectorSha256 = $preparedImageLayoutIndependent.vectorSha256
            layoutProfile = $preparedImageLayoutIndependent.layoutProfile
            resourcePreparationProfile = $preparedImageLayoutIndependent.resourcePreparationProfile
            intrinsicSource = $preparedImageLayoutIndependent.intrinsicSource
            layoutImplementation = $preparedImageLayoutIndependent.layoutImplementation
        }
        outputPngIndependent = [ordered]@{
            verifier = $outputPngIndependent.verifier
            assurance = $outputPngIndependent.assurance
            surfaceCases = $outputPngIndependent.surfaceCases
            pngCases = $outputPngIndependent.pngCases
            checks = $outputPngIndependent.checks
            vectorSha256 = $outputPngIndependent.vectorSha256
            outputProfile = $outputPngIndependent.outputProfile
        }
        enginePngIndependent = [ordered]@{
            verifier = $enginePngIndependent.verifier
            assurance = $enginePngIndependent.assurance
            renderedCases = $enginePngIndependent.renderedCases
            unsupportedCases = $enginePngIndependent.unsupportedCases
            checks = $enginePngIndependent.checks
            vectorSha256 = $enginePngIndependent.vectorSha256
            enginePngKernel = $enginePngIndependent.enginePngKernel
        }
        enginePreparedImagePngIndependent = [ordered]@{
            verifier = $enginePreparedImagePngIndependent.verifier
            assurance = $enginePreparedImagePngIndependent.assurance
            renderedCases = $enginePreparedImagePngIndependent.renderedCases
            unsupportedCases = $enginePreparedImagePngIndependent.unsupportedCases
            checks = $enginePreparedImagePngIndependent.checks
            vectorSha256 = $enginePreparedImagePngIndependent.vectorSha256
            imagePixels = $enginePreparedImagePngIndependent.imagePixels
            degenerateMapping = $enginePreparedImagePngIndependent.degenerateMapping
            samplingMapping = $enginePreparedImagePngIndependent.samplingMapping
            nearestTieRule = $enginePreparedImagePngIndependent.nearestTieRule
            linearArithmetic = $enginePreparedImagePngIndependent.linearArithmetic
            alphaArithmetic = $enginePreparedImagePngIndependent.alphaArithmetic
            enginePreparedImageKernel = $enginePreparedImagePngIndependent.enginePreparedImageKernel
        }
        boundary = [ordered]@{
            rendererProfiles = @()
            profileAvailability = 'NOT_REGISTERED'
            certificationStatus = 'NOT_CERTIFIED'
            rasterImplementation = 'ABSENT'
            resourceManifestAdmission = 'TYPED_STATIC_PREFLIGHT_AUTOMATED_VERIFIED'
            resourceLeaseAdmission = 'COMMAND_DEADLINE_PLUS_5000MS_AUTOMATED_VERIFIED'
            resourceBodyIntegrityKernel = 'PHYSICAL_FETCH_BUDGET_LENGTH_SHA256_AUTOMATED_VERIFIED_WIRED'
            resourceFetchTargetAdmission = 'CANONICAL_HTTPS_EXACT_ORIGIN_SEGMENT_PREFIX_AUTOMATED_VERIFIED'
            resourceFetchTransport = 'RUSTLS_HTTPS_AUTOMATED_VERIFIED'
            resourceMediaDescriptor = 'MEDIA_DESCRIPTOR_PREFLIGHT_AUTOMATED_VERIFIED_UNWIRED'
            requestRawCache = 'REQUEST_LOCAL_CONTENT_ADDRESSED_268435456_BYTES_AUTOMATED_VERIFIED_UNWIRED'
            imageDecodeKernel = 'STATIC_PNG_JPEG_WEBP_STRAIGHT_RGBA8_ORIENTED_AUTOMATED_VERIFIED_UNWIRED_A1_CODEC_PIXELS'
            requestDecodedCache = 'REQUEST_LOCAL_CONTENT_ADDRESSED_536870912_BYTES_AUTOMATED_VERIFIED_UNWIRED'
            fontPrepareKernel = 'ASSET_APPROVED_TTF_GLYF_CFF_CMAP_DESCRIPTOR_FACTS_AUTOMATED_VERIFIED_UNWIRED_A2'
            requestPreparedFontCache = 'REQUEST_LOCAL_CONTENT_ADDRESSED_32_FONTS_4096_TABLES_AUTOMATED_VERIFIED_UNWIRED'
            daemonResourcePreparation = 'MANIFEST_ORDER_FETCH_RAW_IMAGE_FONT_AUTOMATED_VERIFIED_WIRED'
            fontShaping = 'UNWIRED'
            nativeFontStack = 'BUILD_NOT_AUTHORIZED'
            resultSealKernel = 'CANONICAL_METADATA_LENGTH_SHA256_UUID_IMAGE_PAYLOAD_AUTOMATED_VERIFIED_UNWIRED'
            resourceBytes = 'FETCHED_AND_INTEGRITY_VERIFIED'
            layoutKernel = "$($definiteLayoutIndependent.layoutImplementation)_AUTOMATED_VERIFIED_UNWIRED"
            preparedImageLayout = 'PREPARED_IMAGE_FIXED_FILL_SINGLE_AXIS_HUG_LOGICAL_RATIO_ABSOLUTE_STACK_GRID_CONTAINER_AUTOMATED_VERIFIED_UNWIRED'
            outputPngKernel = 'AUTOMATED_VERIFIED_UNWIRED'
            enginePngKernel = 'PREORDER_DEFINITE_IDENTITY_GROUP_FRAME_STACK_GRID_COMPOSITION_VIEWPORT_SOURCE_CANVAS_BACKGROUND_CONTAIN_CENTER_HOST_SOURCE_HARD_CLIP_RECT_PIXEL_ALIGNED_SOLID_ALPHA_PREMULTIPLIED_SOURCE_OVER_SUBTREE_OPACITY_ROUND_HALF_UP_ISOLATION_RECTANGULAR_CLIP_VISIBILITY_ZERO_OPACITY_SUPPRESSION_PNG_KERNEL_PROFILE_GATED'
            preparedImageEnginePngKernel = 'PREPARED_IMAGE_INTEGER_BOX_CONTAIN_COVER_FILL_NEAREST_LINEAR_EXACT_RATIONAL_PREMULTIPLIED_SOURCE_OVER_CENTERED_UNIT_QUARTER_TURN_SUBTREE_OPACITY_EXACT_PNG_AUTOMATED_VERIFIED_PROFILE_GATED'
            daemonOutputPath = 'UNWIRED'
            rendererReady = $false
            ticket19Closed = $false
            providerAttempts = 0
            apiKeysRead = 0
            paidExternalCalls = 0
            publicRenderRouteAdded = $true
        }
    }
    Write-Utf8File -Path $summaryPath -Content ($summary | ConvertTo-Json -Depth 6)
    Write-Host (('Renderer process: Java={0} Python={1}+{2}+{3}+{4}+{5}+{6}+{7}+{8}+{9}+{10}+{11}+{12}+{13}+{14}+{15} Rust Windows=PASS ' +
                'Linux UDS=PASS Profile=NOT_REGISTERED Certification=NOT_CERTIFIED Raster=ABSENT') -f
            $java.tests, $independent.checks, $documentIndependent.total,
            $resourceBodyIndependent.total, $resourceFetchTargetIndependent.total,
            $resourceFetchTransportIndependent.total, $layoutPreflightIndependent.total,
            $definiteLayoutIndependent.total, $preparedImageLayoutIndependent.total,
            $outputPngIndependent.total, $enginePngIndependent.total,
            $resourceMediaRawCacheIndependent.total, $imageDecodeCacheIndependent.total,
            $fontPrepareCacheIndependent.total, $resourcePreparationPipelineIndependent.total,
            $enginePreparedImagePngIndependent.total)
    Write-Host "Renderer process evidence: $summaryPath"
}
finally {
    Pop-Location
}
