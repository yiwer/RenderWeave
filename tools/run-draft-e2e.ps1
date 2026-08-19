[CmdletBinding()]
param(
    [ValidateSet('draft', 'inference')]
    [string]$Journey = 'draft',
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$webRoot = Join-Path $repoRoot 'web'
$jarPath = Join-Path $repoRoot 'renderweave-app\target\renderweave-app-1.0-SNAPSHOT.jar'
$runDir = Join-Path $repoRoot ".sdlc\$Journey-e2e\$PID"
$evidenceDir = if ($EvidenceDir) {
    Join-Path $EvidenceDir "$Journey-journey"
}
else {
    Join-Path $repoRoot ".sdlc\evidence\$Journey-e2e-$PID"
}
$null = New-Item -ItemType Directory -Path $runDir -Force
$null = New-Item -ItemType Directory -Path $evidenceDir -Force
$containerName = "renderweave-$Journey-e2e-$PID"
$containerId = $null
$apiProcess = $null
$webProcess = $null
$apiPort = $null
$webPort = $null
$viteCli = $null
$oldEnvironment = @{}
$journeyStarted = Get-Date
$journeyResult = 'failed'
$journeyFailure = $null
$script:cimUnavailable = $false
$revision = (& git -C $repoRoot rev-parse --verify HEAD)
$status = (& git -C $repoRoot status --porcelain=v1 2>&1) -join "`n"

function Write-ArtifactManifest {
    $rows = foreach ($file in Get-ChildItem -LiteralPath $evidenceDir -Recurse -File | Sort-Object FullName) {
        if ($file.Name -eq 'artifact-manifest.sha256') {
            continue
        }
        $relativePath = $file.FullName.Substring($evidenceDir.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relativePath"
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        (Join-Path $evidenceDir 'artifact-manifest.sha256'),
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

function Wait-ForHttp {
    param(
        [string]$Uri,
        [System.Diagnostics.Process]$Process,
        [string]$Name
    )
    foreach ($attempt in 1..120) {
        if ($Process.HasExited) {
            throw "$Name exited before becoming ready (exit $($Process.ExitCode))."
        }
        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        }
        catch {
            Start-Sleep -Milliseconds 250
        }
    }
    throw "$Name did not become ready within 30 seconds."
}

function Get-JourneyProcessIds {
    $result = @()
    $processNames = @()
    if ($apiPort) {
        $processNames += 'java.exe'
    }
    if ($viteCli -and $webPort) {
        $processNames += 'node.exe'
    }
    if (-not $processNames) {
        return @()
    }
    $filter = "Name = '" + ($processNames -join "' OR Name = '") + "'"
    $instances = @()
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        foreach ($attempt in 1..3) {
            try {
                $instances = @(Get-CimInstance Win32_Process -Filter $filter -ErrorAction Stop)
                break
            }
            catch {
                if ($attempt -ge 3) {
                    $script:cimUnavailable = $true
                    return @()
                }
                Start-Sleep -Milliseconds 300
            }
        }
        foreach ($instance in $instances) {
            $commandLine = $instance.CommandLine
            if ($null -eq $commandLine) {
                continue
            }
            if ($instance.Name -eq 'java.exe' `
                    -and $commandLine -like "*$jarPath*" `
                    -and $commandLine -like "*--server.port=$apiPort*") {
                $result += $instance.ProcessId
            }
            elseif ($instance.Name -eq 'node.exe' `
                    -and $commandLine -like "*$viteCli*" `
                    -and $commandLine -like "*--port $webPort*") {
                $result += $instance.ProcessId
            }
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    return @($result | Sort-Object -Unique)
}

try {
    if (-not (Test-Path -LiteralPath $jarPath)) {
        throw "Application jar is missing: $jarPath. Run the server gate first."
    }

    $nodeDir = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'ensure-node24.ps1') | Select-Object -Last 1)
    if ($LASTEXITCODE -ne 0 -or -not $nodeDir) {
        throw 'Unable to provision the pinned Node 24 toolchain.'
    }
    $nodeExe = Join-Path $nodeDir 'node.exe'
    $pythonExe = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'ensure-python-playwright.ps1') | Select-Object -Last 1)
    if ($LASTEXITCODE -ne 0 -or -not $pythonExe) {
        throw 'Unable to provision the pinned Python Playwright toolchain.'
    }
    $viteCli = Join-Path $webRoot 'node_modules\vite\bin\vite.js'
    if (-not (Test-Path -LiteralPath $viteCli)) {
        throw 'Vite is not installed. Run the web gate first.'
    }

    $containerId = (& docker run --detach --rm `
        --name $containerName `
        --env POSTGRES_DB=renderweave `
        --env POSTGRES_USER=renderweave `
        --env POSTGRES_PASSWORD=renderweave-e2e `
        --publish '127.0.0.1::5432' `
        postgres:16-alpine).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $containerId) {
        throw 'Unable to start the PostgreSQL E2E container.'
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
        throw 'PostgreSQL E2E container did not become ready within 15 seconds.'
    }

    $portBinding = (& docker port $containerName '5432/tcp' | Select-Object -First 1).Trim()
    $databasePort = [int]($portBinding -replace '^.*:', '')
    $apiPort = Get-FreeTcpPort
    $webPort = Get-FreeTcpPort

    foreach ($name in @(
        'RENDERWEAVE_DB_URL', 'RENDERWEAVE_DB_USERNAME', 'RENDERWEAVE_DB_PASSWORD',
        'RENDERWEAVE_API_URL', 'RENDERWEAVE_BLOB_ROOT', 'RENDERWEAVE_LIVE_E2E',
        'RENDERWEAVE_WEB_PORT', 'RENDERWEAVE_EVIDENCE_DIR',
        'RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR', 'RENDERWEAVE_PLAYWRIGHT_HTML_DIR'
    )) {
        $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $env:RENDERWEAVE_DB_URL = "jdbc:postgresql://127.0.0.1:$databasePort/renderweave"
    $env:RENDERWEAVE_DB_USERNAME = 'renderweave'
    $env:RENDERWEAVE_DB_PASSWORD = 'renderweave-e2e'
    $env:RENDERWEAVE_BLOB_ROOT = Join-Path $runDir 'blobs'

    $javaExecutable = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    else {
        (Get-Command java -ErrorAction Stop).Source
    }
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        throw "Java executable is missing: $javaExecutable"
    }
    $apiProcess = Start-Process -FilePath $javaExecutable `
        -ArgumentList @('-jar', $jarPath, "--server.port=$apiPort") `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runDir 'api.stdout.log') `
        -RedirectStandardError (Join-Path $runDir 'api.stderr.log') `
        -PassThru
    Wait-ForHttp -Uri "http://127.0.0.1:$apiPort/api/v1/system/status" -Process $apiProcess -Name 'RenderWeave API'

    $env:RENDERWEAVE_API_URL = "http://127.0.0.1:$apiPort"
    $webProcess = Start-Process -FilePath $nodeExe `
        -ArgumentList @($viteCli, '--host', '127.0.0.1', '--port', "$webPort", '--strictPort') `
        -WorkingDirectory $webRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runDir 'web.stdout.log') `
        -RedirectStandardError (Join-Path $runDir 'web.stderr.log') `
        -PassThru
    $readinessPath = if ($Journey -eq 'inference') { 'inference' } else { 'schemas/new' }
    Wait-ForHttp -Uri "http://127.0.0.1:$webPort/$readinessPath" -Process $webProcess -Name 'RenderWeave web'

    if ($Journey -eq 'inference') {
        $npmCommand = Join-Path $nodeDir 'npm.cmd'
        $env:RENDERWEAVE_LIVE_E2E = '1'
        $env:RENDERWEAVE_WEB_PORT = "$webPort"
        $env:RENDERWEAVE_EVIDENCE_DIR = $evidenceDir
        $env:RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR = Join-Path $evidenceDir 'pw'
        $env:RENDERWEAVE_PLAYWRIGHT_HTML_DIR = Join-Path $evidenceDir 'pw-report'
        Push-Location $webRoot
        try {
            & $npmCommand run test:e2e -- inference-live.spec.ts
        }
        finally {
            Pop-Location
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Inference browser audit failed with exit code $LASTEXITCODE."
        }
    }
    else {
        $schemaKey = "browser-card-$PID"
        & $pythonExe tools\draft_journey_audit.py `
            --base-url "http://127.0.0.1:$webPort" `
            --schema-key $schemaKey `
            --output $evidenceDir
        if ($LASTEXITCODE -ne 0) {
            throw "Draft browser audit failed with exit code $LASTEXITCODE."
        }
    }
    $journeyResult = 'passed'
}
catch {
    $journeyFailure = $_.Exception.Message
    foreach ($path in @(
        (Join-Path $runDir 'api.stdout.log'),
        (Join-Path $runDir 'api.stderr.log'),
        (Join-Path $runDir 'web.stdout.log'),
        (Join-Path $runDir 'web.stderr.log')
    )) {
        if (Test-Path -LiteralPath $path) {
            Get-Content -Encoding UTF8 $path | Select-Object -Last 80 | Write-Error
        }
    }
    throw
}
finally {
    $cleanupWarnings = @()
    $ownedProcessIds = @()
    foreach ($process in @($webProcess, $apiProcess)) {
        if ($process) {
            $ownedProcessIds += $process.Id
        }
    }
    $ownedProcessIds += @(Get-JourneyProcessIds)
    foreach ($processId in ($ownedProcessIds | Sort-Object -Unique)) {
        $ownedProcess = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($ownedProcess) {
            Stop-Process -InputObject $ownedProcess -Force -ErrorAction SilentlyContinue
            try {
                $null = $ownedProcess.WaitForExit(5000)
            }
            catch {
                # The process exited between the handle check and the wait; nothing left to stop.
            }
        }
    }
    foreach ($cleanupAttempt in 1..3) {
        Start-Sleep -Milliseconds 100
        $lateProcessIds = @(Get-JourneyProcessIds)
        if (-not $lateProcessIds) {
            break
        }
        foreach ($processId in $lateProcessIds) {
            $lateProcess = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($lateProcess) {
                Stop-Process -InputObject $lateProcess -Force -ErrorAction SilentlyContinue
                try {
                    $null = $lateProcess.WaitForExit(5000)
                }
                catch {
                }
            }
        }
    }
    $remainingProcessIds = @(Get-JourneyProcessIds)
    if ($script:cimUnavailable) {
        $cleanupWarnings += 'Get-CimInstance unavailable during cleanup; only owned process handles were reaped, so the leaked-process sweep could not be verified.'
    }
    if ($remainingProcessIds) {
        $cleanupWarnings += "process cleanup failed for PID(s): $($remainingProcessIds -join ', ')."
    }
    if ($containerId) {
        & docker stop $containerName *> $null
    }
    foreach ($name in $oldEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
    }
    $metadata = [ordered]@{
        journey = $Journey
        result = $journeyResult
        assurance = 'A1'
        capturedBy = 'tools/run-draft-e2e.ps1'
        revision = $revision
        workingTreeDirty = [bool]$status
        startedAt = $journeyStarted.ToString('o')
        finishedAt = (Get-Date).ToString('o')
        cleanupMode = if ($script:cimUnavailable) { 'owned-handles-only' } else { 'cim-sweep' }
        cleanupWarning = $cleanupWarnings -join '; '
        failure = $journeyFailure
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        (Join-Path $evidenceDir 'metadata.json'),
        ($metadata | ConvertTo-Json -Depth 4),
        $encoding
    )
    Write-ArtifactManifest
    if ($remainingProcessIds -and $null -eq $journeyFailure) {
        throw "E2E process cleanup failed for PID(s): $($remainingProcessIds -join ', ')."
    }
    if ($remainingProcessIds -and $journeyFailure) {
        Write-Warning "E2E process cleanup failed for PID(s): $($remainingProcessIds -join ', '); journey failure is the primary result."
    }
}
