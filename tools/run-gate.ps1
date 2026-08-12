[CmdletBinding()]
param(
    [ValidateSet('fast', 'server', 'web', 'eval', 'e2e', 'draft-e2e', 'inference-e2e', 'compose', 'runtime', 'document-vision', 'observation-r0', 'layered-r1', 'capacity', 'full')]
    [string]$Gate = 'fast'
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
    $environmentPrefix = 'set "DASHSCOPE_API_KEY=" && set "DASHSCOPE_API_KEY_FILE=" && ' +
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
        'eval' { @('offline-eval') }
        'e2e' { @('prototype-e2e') }
        'draft-e2e' { @('server-verify', 'web-node24', 'draft-browser-e2e') }
        'inference-e2e' { @('server-verify', 'web-node24', 'inference-browser-e2e') }
        'compose' { @('compose-config') }
        'runtime' { @('runtime-canary') }
        'document-vision' { @('document-vision-adapter-tests', 'document-vision-canary') }
        'observation-r0' { @('document-observation-r0') }
        'layered-r1' { @('layered-evaluation-r1') }
        'capacity' { @('capacity-baseline') }
        'full' { @('repository-diff', 'server-verify', 'web-node24', 'offline-eval', 'layered-evaluation-r1', 'compose-config', 'runtime-canary', 'document-vision-adapter-tests', 'prototype-e2e', 'draft-browser-e2e', 'inference-browser-e2e') }
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
                    Invoke-ZeroPaidAiCommand `
                        'python.exe tools\document-vision\test_rapidocr_adapter.py'
                }
            }
            'document-vision-canary' {
                Invoke-GateStep $step {
                    Invoke-ZeroPaidAiCommand `
                        'set "RENDERWEAVE_RUN_DOCUMENT_VISION_CANARY=true" && mvn.cmd -B -ntp -pl renderweave-app -am -Dtest=DocumentVisionRuntimeCanaryTest -Dsurefire.failIfNoSpecifiedTests=false test'
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
