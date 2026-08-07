[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jarPath = Join-Path $repoRoot 'renderweave-app\target\renderweave-app-1.0-SNAPSHOT.jar'
$runDir = Join-Path $repoRoot ".sdlc\runtime-canary\$PID"
$null = New-Item -ItemType Directory -Path $runDir -Force
$containerName = "renderweave-canary-$PID"
$containerId = $null
$apiProcess = $null
$oldEnvironment = @{}

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
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "Application jar is missing: $jarPath. Run the server gate first."
    }

    $containerId = (& docker run --detach --rm `
        --name $containerName `
        --env POSTGRES_DB=renderweave `
        --env POSTGRES_USER=renderweave `
        --env POSTGRES_PASSWORD=renderweave-canary `
        --publish '127.0.0.1::5432' `
        postgres:16-alpine).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $containerId) {
        throw 'Unable to start the PostgreSQL canary container.'
    }

    $databaseReady = $false
    foreach ($attempt in 1..60) {
        & docker exec $containerName pg_isready -U renderweave -d renderweave *> $null
        if ($LASTEXITCODE -eq 0) {
            $databaseReady = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $databaseReady) {
        throw 'PostgreSQL canary did not become ready within 15 seconds.'
    }

    $portBinding = (& docker port $containerName '5432/tcp' | Select-Object -First 1).Trim()
    $databasePort = [int]($portBinding -replace '^.*:', '')
    $apiPort = Get-FreeTcpPort

    foreach ($name in @('RENDERWEAVE_DB_URL', 'RENDERWEAVE_DB_USERNAME', 'RENDERWEAVE_DB_PASSWORD')) {
        $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $env:RENDERWEAVE_DB_URL = "jdbc:postgresql://127.0.0.1:$databasePort/renderweave"
    $env:RENDERWEAVE_DB_USERNAME = 'renderweave'
    $env:RENDERWEAVE_DB_PASSWORD = 'renderweave-canary'

    $stdoutPath = Join-Path $runDir 'api.stdout.log'
    $stderrPath = Join-Path $runDir 'api.stderr.log'
    $apiProcess = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $jarPath, "--server.port=$apiPort") `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru

    $response = $null
    foreach ($attempt in 1..120) {
        if ($apiProcess.HasExited) {
            throw "API exited before becoming ready (exit $($apiProcess.ExitCode))."
        }
        try {
            $response = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/api/v1/system/status" -TimeoutSec 2
            if ($response.status -eq 'ready' -and $response.database -eq 'ready') {
                break
            }
        }
        catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $response -or $response.status -ne 'ready' -or $response.database -ne 'ready') {
        throw 'API canary did not become ready within 30 seconds.'
    }

    [ordered]@{
        status = 'passed'
        service = $response.service
        database = $response.database
        contractVersion = $response.contractVersion
        postgresImage = 'postgres:16-alpine'
        ephemeral = $true
    } | ConvertTo-Json -Compress | Write-Output
}
catch {
    foreach ($path in @($stdoutPath, $stderrPath)) {
        if ($path -and (Test-Path -LiteralPath $path)) {
            Get-Content -Encoding UTF8 $path | Select-Object -Last 80 | Write-Error
        }
    }
    throw
}
finally {
    if ($apiProcess -and -not $apiProcess.HasExited) {
        Stop-Process -Id $apiProcess.Id -Force
    }
    if ($containerId) {
        & docker stop $containerName *> $null
    }
    foreach ($name in $oldEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
    }
}
