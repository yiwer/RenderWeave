[CmdletBinding()]
param(
    [ValidateSet('fast', 'server', 'web', 'template', 'asset', 'render', 'eval', 'e2e', 'draft-e2e', 'inference-e2e', 'compose', 'runtime', 'document-vision', 'observation-r0', 'layered-r1', 'image-only-p0', 'image-only-p1-preflight', 'image-only-p1-live', 'image-only-successor', 'image-only-successor-diagnostic-live', 'image-only-v48-successor', 'image-only-v48-successor-diagnostic-live', 'image-only-v49-provenance', 'image-only-v49-envelope', 'image-only-v49-correction', 'image-only-v49-successor', 'image-only-v49-diagnostic-preparation', 'image-only-v49-successor-diagnostic-live', 'image-only-v49-diagnostic-postclose', 'image-only-v50-successor', 'image-only-v50-diagnostic-preparation', 'image-only-v50-successor-diagnostic-live', 'image-only-v50-diagnostic-postclose', 'image-only-v51-successor', 'image-only-v51-diagnostic-preparation', 'image-only-v51-successor-diagnostic-live', 'image-only-v51-diagnostic-postclose', 'image-only-v52-successor', 'image-only-v52-diagnostic-preparation', 'image-only-v52-successor-diagnostic-live', 'image-only-v52-diagnostic-postclose', 'image-only-p2-admission', 'image-only-p2-confirmation', 'image-only-p2-encryption', 'image-only-p2-payload-lifecycle', 'image-only-p2-audit-dual-switch', 'image-only-p2-ocr-sidecar', 'capacity', 'full')]
    [string]$Gate = 'fast',

    [string]$ImageOnlyCanaryInputDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$evidenceDir = Join-Path $repoRoot ".sdlc\evidence\$timestamp-$Gate"
$null = New-Item -ItemType Directory -Path $evidenceDir -Force
$stepRecords = @()
$gateStarted = Get-Date
$revision = 'UNKNOWN'
$status = ''
$failure = $null

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Invoke-ZeroPaidAiCommand {
    param([Parameter(Mandatory = $true)][string]$CommandLine)
    # Use a child process so the caller's secret is neither read nor mutated. Dedicated paid
    # certification deliberately does not go through this project-gate helper.
    $environmentPrefix = 'set "DASHSCOPE_TOKEN_API_KEY=" && set "DASHSCOPE_TOKEN_API_KEY_FILE=" && ' +
        'set "DASHSCOPE_API_KEY=" && set "DASHSCOPE_API_KEY_FILE=" && ' +
        'set "RENDERWEAVE_RUN_LIVE_CANARY=" && set "RENDERWEAVE_RUN_LIVE_CERTIFICATION=" && ' +
        'set "RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION=" && ' +
        'set "RENDERWEAVE_RUN_VISUAL_EVALUATION=" && ' +
        'set "RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION=" && ' +
        'set "RENDERWEAVE_LIVE_AI_ENABLED=false" && set "RENDERWEAVE_LIVE_UPLOAD_ENABLED=false" && '
    & cmd.exe /d /s /c ($environmentPrefix + $CommandLine)
}

function Get-RepositoryManifest {
    Push-Location $repoRoot
    try {
        $paths = @(& git ls-files --cached --others --exclude-standard) | Sort-Object -Unique
        $rows = foreach ($relativePath in $paths) {
            $absolutePath = Join-Path $repoRoot $relativePath
            if (Test-Path -LiteralPath $absolutePath -PathType Leaf) {
                $hash = (Get-FileHash -LiteralPath $absolutePath -Algorithm SHA256).Hash.ToLowerInvariant()
                "$hash  $relativePath"
            }
        }
        return @($rows)
    }
    finally {
        Pop-Location
    }
}

function Invoke-GateStep {
    param(
        [string]$Name,
        [scriptblock]$Action
    )
    $started = Get-Date
    $logPath = Join-Path $evidenceDir "$Name.log"
    Write-Host "`n==> $Name"
    $global:LASTEXITCODE = 0
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Action 2>&1 | Tee-Object -FilePath $logPath
        $stepSucceeded = $?
        $exitCode = $global:LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($null -eq $exitCode) {
        $exitCode = 0
    }
    if (-not $stepSucceeded -and $exitCode -eq 0) {
        $exitCode = 1
    }
    $script:stepRecords += [pscustomobject][ordered]@{
        name = $Name
        exitCode = $exitCode
        durationSeconds = [Math]::Round(((Get-Date) - $started).TotalSeconds, 3)
        log = (Split-Path -Leaf $logPath)
    }
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode."
    }
}

Push-Location $repoRoot
try {
    $revision = (& git rev-parse --verify --quiet HEAD)
    if ($LASTEXITCODE -ne 0) {
        $revision = 'UNBORN'
    }
    $status = (& git status --porcelain=v1 2>&1) -join "`n"
    Write-Utf8File -Path (Join-Path $evidenceDir 'git-status.txt') -Content $status
    $manifest = Get-RepositoryManifest
    Write-Utf8File -Path (Join-Path $evidenceDir 'input-manifest.sha256') -Content ($manifest -join "`n")

    $requestedSteps = switch ($Gate) {
        'fast' { @('repository-diff', 'server-package', 'web-typecheck') }
        'server' { @('server-verify') }
        'web' { @('web-node24') }
        'template' { @('repository-diff', 'template-kernel-replay', 'template-static-replay') }
        'asset' { @('repository-diff', 'asset-kernel-replay') }
        'render' { @('repository-diff', 'renderer-process-replay') }
        'eval' { @('offline-eval') }
        'e2e' { @('prototype-e2e') }
        'draft-e2e' { @('server-verify', 'web-node24', 'draft-browser-e2e') }
        'inference-e2e' { @('server-verify', 'web-node24', 'inference-browser-e2e') }
        'compose' { @('compose-config') }
        'runtime' { @('runtime-canary') }
        'document-vision' { @('document-vision-adapter-tests', 'document-vision-canary') }
        'observation-r0' { @('document-observation-r0') }
        'layered-r1' { @('document-observation-r0', 'layered-evaluation-r1') }
        'image-only-p0' { @('image-only-certification-p0') }
        'image-only-p1-preflight' { @('image-only-certification-p1-preflight') }
        'image-only-p1-live' { @('image-only-certification-p1-live') }
        'image-only-successor' { @('image-only-v48-successor') }
        'image-only-successor-diagnostic-live' { @('image-only-v47-successor-diagnostic-live') }
        'image-only-v48-successor' { @('image-only-v48-successor') }
        'image-only-v48-successor-diagnostic-live' { @('image-only-v48-successor-diagnostic-live') }
        'image-only-v49-provenance' { @('image-only-v49-provenance') }
        'image-only-v49-envelope' { @('image-only-v49-envelope') }
        'image-only-v49-correction' { @('image-only-v49-correction') }
        'image-only-v49-successor' { @('image-only-v49-successor') }
        'image-only-v49-diagnostic-preparation' { @('image-only-v49-diagnostic-preparation') }
        'image-only-v49-successor-diagnostic-live' { @('image-only-v49-successor-diagnostic-live') }
        'image-only-v49-diagnostic-postclose' { @('image-only-v49-diagnostic-postclose') }
        'image-only-v50-successor' { @('image-only-v50-successor') }
        'image-only-v50-diagnostic-preparation' { @('image-only-v50-diagnostic-preparation') }
        'image-only-v50-successor-diagnostic-live' { @('image-only-v50-successor-diagnostic-live') }
        'image-only-v50-diagnostic-postclose' { @('image-only-v50-diagnostic-postclose') }
        'image-only-v51-successor' { @('image-only-v51-successor') }
        'image-only-v51-diagnostic-preparation' { @('image-only-v51-diagnostic-preparation') }
        'image-only-v51-successor-diagnostic-live' { @('image-only-v51-successor-diagnostic-live') }
        'image-only-v51-diagnostic-postclose' { @('image-only-v51-diagnostic-postclose') }
        'image-only-v52-successor' { @('image-only-v52-successor') }
        'image-only-v52-diagnostic-preparation' { @('image-only-v52-diagnostic-preparation') }
        'image-only-v52-successor-diagnostic-live' { @('image-only-v52-successor-diagnostic-live') }
        'image-only-v52-diagnostic-postclose' { @('image-only-v52-diagnostic-postclose') }
        'image-only-p2-admission' { @('image-only-p2-admission') }
        'image-only-p2-confirmation' { @('image-only-p2-confirmation') }
        'image-only-p2-encryption' { @('image-only-p2-encryption') }
        'image-only-p2-payload-lifecycle' { @('image-only-p2-payload-lifecycle') }
        'image-only-p2-audit-dual-switch' { @('image-only-p2-audit-dual-switch') }
        'image-only-p2-ocr-sidecar' { @('image-only-p2-ocr-sidecar') }
        'capacity' { @('capacity-baseline') }
        'full' { @('repository-diff', 'template-kernel-replay', 'template-static-replay', 'asset-kernel-replay', 'renderer-process-replay', 'server-verify', 'web-node24', 'offline-eval', 'document-observation-r0', 'layered-evaluation-r1', 'image-only-certification-p0', 'compose-config', 'runtime-canary', 'document-vision-adapter-tests', 'prototype-e2e', 'draft-browser-e2e', 'inference-browser-e2e') }
    }

    foreach ($step in $requestedSteps) {
        switch ($step) {
            'repository-diff' {
                Invoke-GateStep $step {
                    & git -c core.autocrlf=false -c core.whitespace=cr-at-eol diff --check
                }
            }
            'server-package' {
                Invoke-GateStep $step { Invoke-ZeroPaidAiCommand 'mvn.cmd -B -ntp -DskipTests package' }
            }
            'server-verify' {
                Invoke-GateStep $step { Invoke-ZeroPaidAiCommand 'mvn.cmd -B -ntp clean verify' }
            }
            'web-typecheck' {
                Invoke-GateStep $step {
                    Invoke-ZeroPaidAiCommand 'npm.cmd --prefix web run typecheck'
                }
            }
            'web-node24' {
                Invoke-GateStep $step {
                    Invoke-ZeroPaidAiCommand `
                        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\run-web-gate.ps1'
                }
            }
            'template-static-replay' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-template-static-gate.ps1 -EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summaryPath = Join-Path $evidenceDir 'template-static-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
                        throw 'Template static gate completed without producing its summary.'
                    }
                }
            }
            'asset-kernel-replay' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-asset-kernel-gate.ps1 -EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summaryPath = Join-Path $evidenceDir 'asset-kernel-independent.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
                        throw 'Asset kernel gate completed without producing its independent report.'
                    }
                }
            }
            'renderer-process-replay' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-render-gate.ps1 -EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summaryPath = Join-Path $evidenceDir 'renderer-process-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
                        throw 'Renderer process gate completed without producing its summary.'
                    }
                }
            }
            'template-kernel-replay' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-template-kernel-gate.ps1 -EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summaryPath = Join-Path $evidenceDir 'template-kernel-independent.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
                        throw 'Template kernel gate completed without producing its independent report.'
                    }
                }
            }
            'offline-eval' {
                Invoke-GateStep $step {
                    $reportPath = Join-Path $evidenceDir 'offline-eval-summary.json'
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-offline-eval.ps1 -ReportPath "' + $reportPath + '"'
                    Invoke-ZeroPaidAiCommand $command
                    if ($LASTEXITCODE -eq 0 -and -not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
                        throw 'Offline evaluation completed without producing its machine-readable report.'
                    }
                }
            }
            'compose-config' {
                Invoke-GateStep $step {
                    Invoke-ZeroPaidAiCommand 'docker compose -f compose.yaml config --quiet'
                }
            }
            'prototype-e2e' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-prototype-audit.ps1 -EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                }
            }
            'runtime-canary' {
                Invoke-GateStep $step {
                    Invoke-ZeroPaidAiCommand `
                        'powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\runtime-canary.ps1'
                }
            }
            'document-vision-adapter-tests' {
                Invoke-GateStep $step {
                    $pythonExecutable = Join-Path $repoRoot `
                        '.sdlc\toolchains\document-vision-venv\Scripts\python.exe'
                    if (-not (Test-Path -LiteralPath $pythonExecutable -PathType Leaf)) {
                        throw 'Frozen document-vision Python executable is unavailable.'
                    }
                    Invoke-ZeroPaidAiCommand `
                        ('"' + $pythonExecutable + '" tools\document-vision\test_rapidocr_adapter.py')
                }
            }
            'document-vision-canary' {
                Invoke-GateStep $step {
                    $pythonExecutable = Join-Path $repoRoot `
                        '.sdlc\toolchains\document-vision-venv\Scripts\python.exe'
                    $adapterScript = Join-Path $repoRoot `
                        'tools\document-vision\rapidocr_adapter.py'
                    $modelRoot = Join-Path $repoRoot `
                        '.sdlc\toolchains\document-vision-venv\Lib\site-packages\rapidocr\models'
                    if (-not (Test-Path -LiteralPath $pythonExecutable -PathType Leaf) -or
                            -not (Test-Path -LiteralPath $adapterScript -PathType Leaf) -or
                            -not (Test-Path -LiteralPath $modelRoot -PathType Container)) {
                        throw 'Frozen RapidOCR/OpenVINO toolchain is unavailable.'
                    }
                    $command = 'set "RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY=true" && ' +
                        'set "RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE=' + $pythonExecutable + '" && ' +
                        'set "RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT=' + $adapterScript + '" && ' +
                        'set "RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT=' + $modelRoot + '" && ' +
                        'mvn.cmd -B -ntp -pl renderweave-app -am ' +
                        '-Dtest=DocumentVisionRuntimeCanaryTest ' +
                        '-Dsurefire.failIfNoSpecifiedTests=false test'
                    Invoke-ZeroPaidAiCommand $command
                }
            }
            'document-observation-r0' {
                Invoke-GateStep $step {
                    $reportPath = Join-Path $evidenceDir 'document-observation-r0-summary.json'
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-document-observation-r0.ps1 -ReportPath "' +
                        $reportPath + '"'
                    Invoke-ZeroPaidAiCommand $command
                    if ($LASTEXITCODE -eq 0 -and -not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
                        throw 'Document observation R0 gate completed without producing its report.'
                    }
                }
            }
            'layered-evaluation-r1' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-layered-evaluation-r1.ps1 -EvidenceDir "' +
                        $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $independentSummary = Join-Path $evidenceDir 'layered-r1-independent-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $independentSummary -PathType Leaf)) {
                        throw 'Layered R1 gate completed without producing its independent summary.'
                    }
                }
            }
            'image-only-certification-p0' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-certification-p0.ps1 -EvidenceDir "' +
                        $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $independentSummary = Join-Path $evidenceDir 'image-only-p0-independent.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $independentSummary -PathType Leaf)) {
                        throw 'IMAGE_ONLY P0 gate completed without producing its independent summary.'
                    }
                }
            }
            'image-only-certification-p1-preflight' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY P1 preflight requires a safe -ImageOnlyCanaryInputDirectory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-certification-p1-preflight.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir 'image-only-p1-preflight-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY P1 preflight completed without its summary.'
                    }
                }
            }
            'image-only-certification-p1-live' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory)) {
                        throw 'IMAGE_ONLY P1 live gate requires -ImageOnlyCanaryInputDirectory.'
                    }
                    # This is the sole paid path. It deliberately inherits the caller's credential
                    # environment without inspecting or printing it; the exact J1 is revalidated
                    # inside the dedicated runner before any Provider bytes can leave.
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                        -File tools\run-image-only-certification-canary-live.ps1 `
                        -EvidenceDir $evidenceDir `
                        -InputDirectory $ImageOnlyCanaryInputDirectory
                    if ($LASTEXITCODE -eq 0) {
                        $summary = Join-Path $evidenceDir 'image-only-canary-live-summary.json'
                        if (-not (Test-Path -LiteralPath $summary -PathType Leaf)) {
                            throw 'IMAGE_ONLY P1 live gate completed without its summary.'
                        }
                    }
                }
            }
            'image-only-v47-successor' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v47 successor gate requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v47-successor.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir 'image-only-v47-successor-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v47 successor gate completed without its summary.'
                    }
                }
            }
            'image-only-v48-successor' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v48 successor gate requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v48-successor.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir 'image-only-v48-successor-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v48 successor gate completed without its summary.'
                    }
                }
            }
            'image-only-v47-successor-diagnostic-live' {
                Invoke-GateStep $step {
                    throw 'IMAGE_ONLY v47 diagnostic authorization is CLOSED; automatic rerun is forbidden.'
                }
            }
            'image-only-v48-successor-diagnostic-live' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory)) {
                        throw 'IMAGE_ONLY v48 diagnostic live gate requires an input directory.'
                    }
                    # This explicit paid path inherits, but never inspects or prints, the caller's
                    # credential environment. The dedicated runner revalidates the exact J1 and
                    # one-case input before the Provider boundary can issue a permit.
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                        -File tools\run-image-only-v48-profile-successor-diagnostic-live.ps1 `
                        -EvidenceDir $evidenceDir `
                        -InputDirectory $ImageOnlyCanaryInputDirectory
                    if ($LASTEXITCODE -eq 0) {
                        $summary = Join-Path $evidenceDir `
                            'image-only-v48-diagnostic-live-summary.json'
                        if (-not (Test-Path -LiteralPath $summary -PathType Leaf)) {
                            throw 'IMAGE_ONLY v48 diagnostic live gate completed without its summary.'
                        }
                    }
                }
            }
            'image-only-v49-provenance' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v49-provenance.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v49-provenance-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v49 provenance gate completed without its summary.'
                    }
                }
            }
            'image-only-v49-envelope' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v49-envelope.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v49-envelope-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v49 envelope gate completed without its summary.'
                    }
                }
            }
            'image-only-v49-correction' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v49-correction.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v49-correction-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v49 correction gate completed without its summary.'
                    }
                }
            }
            'image-only-v49-successor' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v49-successor.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v49-successor-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v49 successor gate completed without its summary.'
                    }
                }
            }
            'image-only-v50-successor' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v50-successor.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v50-successor-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v50 successor gate completed without its summary.'
                    }
                }
            }
            'image-only-v51-successor' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v51-successor.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v51-successor-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v51 successor gate completed without its summary.'
                    }
                }
            }
            'image-only-v52-successor' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v52-successor.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v52-successor-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v52 successor gate completed without its summary.'
                    }
                }
            }
            'image-only-v52-diagnostic-preparation' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v52 diagnostic preparation requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v52-diagnostic-preparation.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v52-diagnostic-preparation-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v52 diagnostic preparation completed without its summary.'
                    }
                }
            }
            'image-only-v52-successor-diagnostic-live' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory)) {
                        throw 'IMAGE_ONLY v52 diagnostic live gate requires an input directory.'
                    }
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                        -File tools\run-image-only-v52-profile-successor-diagnostic-live.ps1 `
                        -EvidenceDir $evidenceDir `
                        -InputDirectory $ImageOnlyCanaryInputDirectory
                    if ($LASTEXITCODE -eq 0) {
                        $summary = Join-Path $evidenceDir `
                            'image-only-v52-diagnostic-live-summary.json'
                        if (-not (Test-Path -LiteralPath $summary -PathType Leaf)) {
                            throw 'IMAGE_ONLY v52 diagnostic live gate completed without its summary.'
                        }
                    }
                }
            }
            'image-only-v52-diagnostic-postclose' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v52 post-close gate requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v52-diagnostic-postclose.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v52-diagnostic-postclose-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v52 post-close gate completed without its summary.'
                    }
                }
            }
            'image-only-p2-admission' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-p2-admission.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir 'image-only-p2-admission-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY P2 admission gate completed without its summary.'
                    }
                }
            }
            'image-only-p2-confirmation' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-p2-confirmation.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir 'image-only-p2-confirmation-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY P2 confirmation gate completed without its summary.'
                    }
                }
            }
            'image-only-p2-encryption' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-p2-encryption.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir 'image-only-p2-encryption-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY P2 encryption gate completed without its summary.'
                    }
                }
            }
            'image-only-p2-payload-lifecycle' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-p2-payload-lifecycle.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-p2-payload-lifecycle-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY P2 payload lifecycle gate completed without its summary.'
                    }
                }
            }
            'image-only-p2-audit-dual-switch' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-p2-audit-dual-switch.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-p2-audit-dual-switch-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY P2 audit/dual-switch gate completed without its summary.'
                    }
                }
            }
            'image-only-p2-ocr-sidecar' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-p2-ocr-sidecar.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-p2-ocr-sidecar-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY P2 OCR sidecar gate completed without its summary.'
                    }
                }
            }
            'image-only-v51-diagnostic-preparation' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v51 diagnostic preparation requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v51-diagnostic-preparation.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v51-diagnostic-preparation-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v51 diagnostic preparation completed without its summary.'
                    }
                }
            }
            'image-only-v51-successor-diagnostic-live' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory)) {
                        throw 'IMAGE_ONLY v51 diagnostic live gate requires an input directory.'
                    }
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                        -File tools\run-image-only-profile-successor-diagnostic-live.ps1 `
                        -EvidenceDir $evidenceDir `
                        -InputDirectory $ImageOnlyCanaryInputDirectory
                    if ($LASTEXITCODE -eq 0) {
                        $summary = Join-Path $evidenceDir `
                            'image-only-v51-diagnostic-live-summary.json'
                        if (-not (Test-Path -LiteralPath $summary -PathType Leaf)) {
                            throw 'IMAGE_ONLY v51 diagnostic live gate completed without its summary.'
                        }
                    }
                }
            }
            'image-only-v51-diagnostic-postclose' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v51 post-close gate requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v51-diagnostic-postclose.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v51-diagnostic-postclose-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v51 post-close gate completed without its summary.'
                    }
                }
            }
            'image-only-v50-diagnostic-preparation' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v50 diagnostic preparation requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v50-diagnostic-preparation.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v50-diagnostic-preparation-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v50 diagnostic preparation completed without its summary.'
                    }
                }
            }
            'image-only-v50-successor-diagnostic-live' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory)) {
                        throw 'IMAGE_ONLY v50 diagnostic live gate requires an input directory.'
                    }
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                        -File tools\run-image-only-v50-profile-successor-diagnostic-live.ps1 `
                        -EvidenceDir $evidenceDir `
                        -InputDirectory $ImageOnlyCanaryInputDirectory
                    if ($LASTEXITCODE -eq 0) {
                        $summary = Join-Path $evidenceDir `
                            'image-only-v50-diagnostic-live-summary.json'
                        if (-not (Test-Path -LiteralPath $summary -PathType Leaf)) {
                            throw 'IMAGE_ONLY v50 diagnostic live gate completed without its summary.'
                        }
                    }
                }
            }
            'image-only-v50-diagnostic-postclose' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v50 post-close gate requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v50-diagnostic-postclose.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v50-diagnostic-postclose-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v50 post-close gate completed without its summary.'
                    }
                }
            }
            'image-only-v49-diagnostic-preparation' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v49 diagnostic preparation requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v49-diagnostic-preparation.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v49-diagnostic-preparation-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v49 diagnostic preparation completed without its summary.'
                    }
                }
            }
            'image-only-v49-successor-diagnostic-live' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory)) {
                        throw 'IMAGE_ONLY v49 diagnostic live gate requires an input directory.'
                    }
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
                        -File tools\run-image-only-v49-profile-successor-diagnostic-live.ps1 `
                        -EvidenceDir $evidenceDir `
                        -InputDirectory $ImageOnlyCanaryInputDirectory
                    if ($LASTEXITCODE -eq 0) {
                        $summary = Join-Path $evidenceDir `
                            'image-only-v49-diagnostic-live-summary.json'
                        if (-not (Test-Path -LiteralPath $summary -PathType Leaf)) {
                            throw 'IMAGE_ONLY v49 diagnostic live gate completed without its summary.'
                        }
                    }
                }
            }
            'image-only-v49-diagnostic-postclose' {
                Invoke-GateStep $step {
                    if ([string]::IsNullOrWhiteSpace($ImageOnlyCanaryInputDirectory) -or
                            $ImageOnlyCanaryInputDirectory.Contains('"')) {
                        throw 'IMAGE_ONLY v49 post-close gate requires a safe input directory.'
                    }
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-image-only-v49-diagnostic-postclose.ps1 ' +
                        '-EvidenceDir "' + $evidenceDir + '" ' +
                        '-InputDirectory "' + $ImageOnlyCanaryInputDirectory + '"'
                    Invoke-ZeroPaidAiCommand $command
                    $summary = Join-Path $evidenceDir `
                        'image-only-v49-diagnostic-postclose-summary.json'
                    if ($LASTEXITCODE -eq 0 -and -not (
                            Test-Path -LiteralPath $summary -PathType Leaf)) {
                        throw 'IMAGE_ONLY v49 post-close gate completed without its summary.'
                    }
                }
            }
            'draft-browser-e2e' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-draft-e2e.ps1 -EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                }
            }
            'inference-browser-e2e' {
                Invoke-GateStep $step {
                    $command = 'powershell.exe -NoProfile -ExecutionPolicy Bypass ' +
                        '-File tools\run-inference-e2e.ps1 -EvidenceDir "' + $evidenceDir + '"'
                    Invoke-ZeroPaidAiCommand $command
                }
            }
            'capacity-baseline' {
                Invoke-GateStep $step {
                    $reportPath = Join-Path $evidenceDir 'capacity-baseline.json'
                    $command = 'set "RENDERWEAVE_RUN_CAPACITY_BASELINE=true" && ' +
                        'set "RENDERWEAVE_CAPACITY_REPORT=' + $reportPath + '" && ' +
                        'mvn.cmd -B -ntp -pl renderweave-app -am ' +
                        '-Dtest=CapacityBaselineTest -Dsurefire.failIfNoSpecifiedTests=false test'
                    Invoke-ZeroPaidAiCommand $command
                    if ($LASTEXITCODE -eq 0 -and -not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
                        throw 'Capacity baseline completed without producing its machine-readable report.'
                    }
                }
            }
        }
    }

    $result = 'passed'
}
catch {
    $result = 'failed'
    $failure = $_.Exception.Message
}
finally {
    $metadata = [ordered]@{
        gate = $Gate
        result = $result
        assurance = 'A1'
        capturedBy = 'tools/run-gate.ps1'
        revision = $revision
        workingTreeDirty = [bool]$status
        startedAt = $gateStarted.ToString('o')
        finishedAt = (Get-Date).ToString('o')
        durationSeconds = [Math]::Round(((Get-Date) - $gateStarted).TotalSeconds, 3)
        steps = $stepRecords
        failure = $failure
    }
    Write-Utf8File -Path (Join-Path $evidenceDir 'metadata.json') -Content ($metadata | ConvertTo-Json -Depth 6)
    Pop-Location
}

Write-Host "`nEvidence: $evidenceDir"
if ($result -ne 'passed') {
    Write-Error $failure
    exit 1
}
