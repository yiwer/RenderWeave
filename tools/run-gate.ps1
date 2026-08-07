[CmdletBinding()]
param(
    [ValidateSet('fast', 'server', 'web', 'e2e', 'draft-e2e', 'inference-e2e', 'compose', 'runtime', 'full')]
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
        'e2e' { @('prototype-e2e') }
        'draft-e2e' { @('server-verify', 'web-node24', 'draft-browser-e2e') }
        'inference-e2e' { @('server-verify', 'web-node24', 'inference-browser-e2e') }
        'compose' { @('compose-config') }
        'runtime' { @('runtime-canary') }
        'full' { @('repository-diff', 'server-verify', 'web-node24', 'compose-config', 'runtime-canary', 'prototype-e2e', 'draft-browser-e2e', 'inference-browser-e2e') }
    }

    foreach ($step in $requestedSteps) {
        switch ($step) {
            'repository-diff' {
                Invoke-GateStep $step { & git -c core.autocrlf=false diff --check }
            }
            'server-package' {
                Invoke-GateStep $step { & mvn.cmd -B -ntp -DskipTests package }
            }
            'server-verify' {
                Invoke-GateStep $step { & mvn.cmd -B -ntp verify }
            }
            'web-typecheck' {
                Invoke-GateStep $step { & npm.cmd --prefix web run typecheck }
            }
            'web-node24' {
                Invoke-GateStep $step {
                    $nodeDir = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\ensure-node24.ps1 | Select-Object -Last 1)
                    if ($LASTEXITCODE -ne 0 -or -not $nodeDir) {
                        throw 'Unable to provision the pinned Node 24 toolchain.'
                    }
                    $npmCommand = Join-Path $nodeDir 'npm.cmd'
                    $previousUserConfig = $env:NPM_CONFIG_USERCONFIG
                    $env:NPM_CONFIG_USERCONFIG = Join-Path $repoRoot 'web\.npmrc'
                    Push-Location (Join-Path $repoRoot 'web')
                    try {
                        & $npmCommand ci
                        if ($LASTEXITCODE -ne 0) { return }
                        & $npmCommand run check
                        if ($LASTEXITCODE -ne 0) { return }
                        & $npmCommand run build
                    }
                    finally {
                        Pop-Location
                        if ($null -eq $previousUserConfig) {
                            Remove-Item Env:NPM_CONFIG_USERCONFIG -ErrorAction SilentlyContinue
                        }
                        else {
                            $env:NPM_CONFIG_USERCONFIG = $previousUserConfig
                        }
                    }
                }
            }
            'compose-config' {
                Invoke-GateStep $step { & docker compose -f compose.yaml config --quiet }
            }
            'prototype-e2e' {
                Invoke-GateStep $step {
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\run-prototype-audit.ps1
                }
            }
            'runtime-canary' {
                Invoke-GateStep $step {
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\runtime-canary.ps1
                }
            }
            'draft-browser-e2e' {
                Invoke-GateStep $step {
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\run-draft-e2e.ps1
                }
            }
            'inference-browser-e2e' {
                Invoke-GateStep $step {
                    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\run-inference-e2e.ps1
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
