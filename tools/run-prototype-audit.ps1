[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$webRoot = Join-Path $repoRoot 'web'
$nodeDir = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'ensure-node24.ps1') | Select-Object -Last 1)
if ($LASTEXITCODE -ne 0 -or -not $nodeDir) {
    throw 'Unable to provision the pinned Node 24 toolchain.'
}
$nodeExe = Join-Path $nodeDir 'node.exe'
$npmCommand = Join-Path $nodeDir 'npm.cmd'
$viteCli = Join-Path $webRoot 'node_modules\vite\bin\vite.js'
$startedProcess = $null
$previousWebPort = $env:RENDERWEAVE_WEB_PORT

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
        & python tools\prototype_audit.py --base-url "http://127.0.0.1:$webPort"
        if ($LASTEXITCODE -ne 0) {
            throw "Prototype browser audit failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($startedProcess -and -not $startedProcess.HasExited) {
        Stop-Process -Id $startedProcess.Id -Force
    }
    if ($null -eq $previousWebPort) {
        Remove-Item Env:RENDERWEAVE_WEB_PORT -ErrorAction SilentlyContinue
    }
    else {
        $env:RENDERWEAVE_WEB_PORT = $previousWebPort
    }
}
