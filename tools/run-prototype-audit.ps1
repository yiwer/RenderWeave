[CmdletBinding()]
param(
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$webRoot = Join-Path $repoRoot 'web'
$nodeDir = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'ensure-node24.ps1') | Select-Object -Last 1)
if ($LASTEXITCODE -ne 0 -or -not $nodeDir) {
    throw 'Unable to provision the pinned Node 24 toolchain.'
}
$nodeExe = Join-Path $nodeDir 'node.exe'
$npmCommand = Join-Path $nodeDir 'npm.cmd'
$pythonExe = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'ensure-python-playwright.ps1') | Select-Object -Last 1)
if ($LASTEXITCODE -ne 0 -or -not $pythonExe) {
    throw 'Unable to provision the pinned Python Playwright toolchain.'
}
$viteCli = Join-Path $webRoot 'node_modules\vite\bin\vite.js'
$startedProcess = $null
$webPort = $null
$previousWebPort = $env:RENDERWEAVE_WEB_PORT
$previousPlaywrightOutput = $env:RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR
$previousPlaywrightHtml = $env:RENDERWEAVE_PLAYWRIGHT_HTML_DIR
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if ($EvidenceDir) {
    $artifactRoot = Join-Path $EvidenceDir 'browser-artifacts'
}
else {
    $artifactRoot = Join-Path $repoRoot ".sdlc\evidence\$timestamp-prototype-audit\browser-artifacts"
}
$playwrightOutput = Join-Path $artifactRoot 'pw'
$playwrightHtml = Join-Path $artifactRoot 'pw-report'
$prototypeOutput = Join-Path $artifactRoot 'prototype'
$null = New-Item -ItemType Directory -Path $artifactRoot -Force

function Write-BrowserArtifactManifest {
    $rows = foreach ($file in Get-ChildItem -LiteralPath $artifactRoot -Recurse -File | Sort-Object FullName) {
        if ($file.Name -eq 'manifest.sha256') {
            continue
        }
        $relativePath = $file.FullName.Substring($artifactRoot.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relativePath"
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        (Join-Path $artifactRoot 'manifest.sha256'),
        ($rows -join "`n"),
        $encoding
    )
}

function Get-FreeTcpPort {
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

try {
    if (-not (Test-Path -LiteralPath $viteCli)) {
        throw 'Vite is not installed. Run npm --prefix web install first.'
    }
    $webPort = Get-FreeTcpPort
    $env:RENDERWEAVE_WEB_PORT = "$webPort"
    $env:RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR = $playwrightOutput
    $env:RENDERWEAVE_PLAYWRIGHT_HTML_DIR = $playwrightHtml
    $startedProcess = Start-Process -FilePath $nodeExe `
        -ArgumentList @($viteCli, '--host', '127.0.0.1', '--port', "$webPort", '--strictPort') `
        -WorkingDirectory $webRoot `
        -WindowStyle Hidden `
        -PassThru

    $ready = $false
    foreach ($attempt in 1..60) {
        Start-Sleep -Milliseconds 250
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$webPort/prototype/schema-studio?variant=A" -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                $ready = $true
                break
            }
        }
        catch {
            if ($startedProcess.HasExited) {
                throw "Vite exited before becoming ready (exit $($startedProcess.ExitCode))."
            }
        }
    }
    if (-not $ready) {
        throw 'Vite did not become HTTP-ready within 15 seconds.'
    }

    Push-Location $webRoot
    try {
        $env:NPM_CONFIG_USERCONFIG = Join-Path $webRoot '.npmrc'
        & $npmCommand run test:e2e
        if ($LASTEXITCODE -ne 0) {
            throw "Playwright E2E failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    Push-Location $repoRoot
    try {
        & $pythonExe tools\prototype_audit.py --base-url "http://127.0.0.1:$webPort" --output $prototypeOutput
        if ($LASTEXITCODE -ne 0) {
            throw "Prototype browser audit failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    Write-BrowserArtifactManifest
    $viteProcessIds = @()
    if ($startedProcess) {
        $viteProcessIds += $startedProcess.Id
    }
    if ($webPort) {
        $viteProcessIds += @(Get-CimInstance Win32_Process | Where-Object {
            $_.Name -eq 'node.exe' `
                -and $_.CommandLine -like "*$viteCli*" `
                -and $_.CommandLine -like "*--port $webPort*"
        } | Select-Object -ExpandProperty ProcessId)
    }
    foreach ($processId in ($viteProcessIds | Sort-Object -Unique)) {
        $runningVite = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($runningVite) {
            Stop-Process -InputObject $runningVite -Force
            $null = $runningVite.WaitForExit(5000)
        }
    }
    $remainingVite = @($viteProcessIds | Where-Object {
        Get-Process -Id $_ -ErrorAction SilentlyContinue
    })
    if ($remainingVite) {
        throw "Vite process cleanup failed for PID(s): $($remainingVite -join ', ')."
    }
    if ($null -eq $previousWebPort) {
        Remove-Item Env:RENDERWEAVE_WEB_PORT -ErrorAction SilentlyContinue
    }
    else {
        $env:RENDERWEAVE_WEB_PORT = $previousWebPort
    }
    if ($null -eq $previousPlaywrightOutput) {
        Remove-Item Env:RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR -ErrorAction SilentlyContinue
    }
    else {
        $env:RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR = $previousPlaywrightOutput
    }
    if ($null -eq $previousPlaywrightHtml) {
        Remove-Item Env:RENDERWEAVE_PLAYWRIGHT_HTML_DIR -ErrorAction SilentlyContinue
    }
    else {
        $env:RENDERWEAVE_PLAYWRIGHT_HTML_DIR = $previousPlaywrightHtml
    }
}
