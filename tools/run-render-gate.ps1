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
$outputPngReport = Join-Path $resolvedEvidenceDir 'output-png-independent.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'renderer-process-summary.json'
foreach ($report in @($independentReport, $documentReport, $outputPngReport, $summaryPath)) {
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
            -or $independent.vendorFileCount -ne 1057 `
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
    if ($documentIndependent.verifier -ne 'renderweave-render-document-python-independent/1' `
            -or $documentIndependent.result -ne 'PASS' `
            -or $documentIndependent.passed -ne 14 `
            -or $documentIndependent.total -ne 14 `
            -or $documentIndependent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $documentIndependent.certificationStatus -ne 'NOT_CERTIFIED' `
            -or $documentIndependent.rasterImplementation -ne 'ABSENT' `
            -or $documentIndependent.providerAttempts -ne 0) {
        throw 'RenderDocument independent report boundary drifted.'
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
        gateVersion = 'renderweave-renderer-process-gate/1.1'
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
            cases = $documentIndependent.total
            catalogSha256 = $documentIndependent.catalogSha256
            vectorsSha256 = $documentIndependent.vectorsSha256
            allKindsCanonicalSha256 = $documentIndependent.allKindsCanonicalSha256
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
        boundary = [ordered]@{
            rendererProfiles = @()
            profileAvailability = 'NOT_REGISTERED'
            certificationStatus = 'NOT_CERTIFIED'
            rasterImplementation = 'ABSENT'
            outputPngKernel = 'AUTOMATED_VERIFIED_UNWIRED'
            daemonOutputPath = 'UNWIRED'
            rendererReady = $false
            ticket19Closed = $false
            providerAttempts = 0
            apiKeysRead = 0
            paidExternalCalls = 0
            publicRenderRouteAdded = $false
        }
    }
    Write-Utf8File -Path $summaryPath -Content ($summary | ConvertTo-Json -Depth 6)
    Write-Host (('Renderer process: Java={0} Python={1}+{2}+{3} Rust Windows=PASS ' +
                'Linux UDS=PASS Profile=NOT_REGISTERED Certification=NOT_CERTIFIED Raster=ABSENT') -f
            $java.tests, $independent.checks, $documentIndependent.total, $outputPngIndependent.total)
    Write-Host "Renderer process evidence: $summaryPath"
}
finally {
    Pop-Location
}
