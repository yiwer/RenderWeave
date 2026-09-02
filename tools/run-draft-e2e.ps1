[CmdletBinding()]
param(
    [ValidateSet('draft', 'inference', 'template-roundtrip')]
    [string]$Journey = 'draft',
    [string]$EvidenceDir,
    [string]$LocalPostgresBin
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$webRoot = Join-Path $repoRoot 'web'
$jarPath = Join-Path $repoRoot 'renderweave-app\target\renderweave-app-1.0-SNAPSHOT.jar'
$runDir = Join-Path $repoRoot ".sdlc\$Journey-e2e\$PID"
$evidenceDir = if ($EvidenceDir) {
    $requestedEvidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceDir)) {
        [System.IO.Path]::GetFullPath($EvidenceDir)
    }
    else {
        [System.IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDir))
    }
    Join-Path $requestedEvidenceRoot "$Journey-journey"
}
else {
    Join-Path $repoRoot ".sdlc\evidence\$Journey-e2e-$PID"
}
$null = New-Item -ItemType Directory -Path $runDir -Force
$null = New-Item -ItemType Directory -Path $evidenceDir -Force
$containerName = "renderweave-$Journey-e2e-$PID"
$containerId = $null
$postgresDataDir = $null
$postgresStarted = $false
$postgresShutdownFailed = $false
$minioContainerName = "renderweave-template-roundtrip-minio-e2e-$PID"
$minioContainerId = $null
$minioContainerShutdownFailed = $false
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
        if ($Process -and $Process.HasExited) {
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

    $databaseUsername = 'renderweave'
    $databasePassword = 'renderweave-e2e'
    if ($LocalPostgresBin) {
        $postgresBin = [System.IO.Path]::GetFullPath($LocalPostgresBin)
        $initDb = Join-Path $postgresBin 'initdb.exe'
        $pgCtl = Join-Path $postgresBin 'pg_ctl.exe'
        $createDb = Join-Path $postgresBin 'createdb.exe'
        foreach ($executable in @($initDb, $pgCtl, $createDb)) {
            if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
                throw "Local PostgreSQL executable is missing: $executable"
            }
        }
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\')
        $postgresDataDir = Join-Path $tempRoot "renderweave-$Journey-e2e-$PID-postgres"
        if (Test-Path -LiteralPath $postgresDataDir) {
            throw "Refusing to reuse the local PostgreSQL E2E directory: $postgresDataDir"
        }
        $null = New-Item -ItemType Directory -Path $postgresDataDir
        & $initDb -D $postgresDataDir '--auth=trust' '--username=postgres' `
            '--encoding=UTF8' '--no-locale'
        if ($LASTEXITCODE -ne 0) {
            throw "Local PostgreSQL initdb failed with exit code $LASTEXITCODE."
        }
        $databasePort = Get-FreeTcpPort
        & $pgCtl -D $postgresDataDir -l (Join-Path $postgresDataDir 'postgres.log') `
            -o "-p $databasePort -h 127.0.0.1" start
        if ($LASTEXITCODE -ne 0) {
            throw "Local PostgreSQL start failed with exit code $LASTEXITCODE."
        }
        $postgresStarted = $true
        & $createDb -h 127.0.0.1 -p $databasePort -U postgres renderweave
        if ($LASTEXITCODE -ne 0) {
            throw "Local PostgreSQL database creation failed with exit code $LASTEXITCODE."
        }
        $databaseUsername = 'postgres'
        $databasePassword = 'unused-local-trust'
    }
    else {
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
    }
    $apiPort = Get-FreeTcpPort
    $webPort = Get-FreeTcpPort

    foreach ($name in @(
        'RENDERWEAVE_DB_URL', 'RENDERWEAVE_DB_USERNAME', 'RENDERWEAVE_DB_PASSWORD',
        'RENDERWEAVE_API_URL', 'RENDERWEAVE_BLOB_ROOT', 'RENDERWEAVE_LIVE_E2E',
        'RENDERWEAVE_TEMPLATE_ROUNDTRIP_LIVE',
        'RENDERWEAVE_TEMPLATE_SINGLE_OWNER_ENABLED',
        'RENDERWEAVE_TEMPLATE_SINGLE_OWNER_SCOPE',
        'RENDERWEAVE_TEMPLATE_SINGLE_OWNER_CAPABILITIES',
        'RENDERWEAVE_ASSET_SINGLE_OWNER_ENABLED',
        'RENDERWEAVE_ASSET_SINGLE_OWNER_SCOPE',
        'RENDERWEAVE_ASSET_SINGLE_OWNER_CAPABILITIES',
        'RENDERWEAVE_ASSET_S3_ENDPOINT',
        'RENDERWEAVE_ASSET_S3_REGION',
        'RENDERWEAVE_ASSET_S3_BUCKET',
        'RENDERWEAVE_ASSET_S3_ACCESS_KEY',
        'RENDERWEAVE_ASSET_S3_SECRET_KEY',
        'RENDERWEAVE_ASSET_S3_PATH_STYLE',
        'RENDERWEAVE_WEB_PORT', 'RENDERWEAVE_EVIDENCE_DIR',
        'RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR', 'RENDERWEAVE_PLAYWRIGHT_HTML_DIR'
    )) {
        $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $env:RENDERWEAVE_DB_URL = "jdbc:postgresql://127.0.0.1:$databasePort/renderweave"
    $env:RENDERWEAVE_DB_USERNAME = $databaseUsername
    $env:RENDERWEAVE_DB_PASSWORD = $databasePassword
    $env:RENDERWEAVE_BLOB_ROOT = Join-Path $runDir 'blobs'
    if ($Journey -eq 'template-roundtrip') {
        $env:RENDERWEAVE_TEMPLATE_SINGLE_OWNER_ENABLED = 'true'
        $env:RENDERWEAVE_TEMPLATE_SINGLE_OWNER_SCOPE = 'template-roundtrip-e2e'
        $env:RENDERWEAVE_TEMPLATE_SINGLE_OWNER_CAPABILITIES = `
            'template.create,template.read,template.update'
        $env:RENDERWEAVE_ASSET_SINGLE_OWNER_ENABLED = 'true'
        $env:RENDERWEAVE_ASSET_SINGLE_OWNER_SCOPE = 'template-roundtrip-e2e'
        $env:RENDERWEAVE_ASSET_SINGLE_OWNER_CAPABILITIES = 'asset.create,asset.read'

        if ([string]::IsNullOrWhiteSpace($env:RENDERWEAVE_ASSET_S3_ENDPOINT)) {
            $minioAccessKey = 'renderweave-local-e2e'
            $minioSecretKey = 'renderweave-local-e2e-secret'
            $minioRegion = 'us-east-1'
            $minioBucket = 'renderweave-assets'
            $minioImage = 'minio/minio:RELEASE.2024-12-18T13-15-44Z'
            $minioContainerId = (& docker run --detach --rm `
                --name $minioContainerName `
                --env "MINIO_ROOT_USER=$minioAccessKey" `
                --env "MINIO_ROOT_PASSWORD=$minioSecretKey" `
                --publish '127.0.0.1::9000' `
                $minioImage server /data --console-address ':9001').Trim()
            if ($LASTEXITCODE -ne 0 -or -not $minioContainerId) {
                throw "Unable to start the pinned MinIO E2E container $minioImage."
            }
            $minioPortBinding = (& docker port $minioContainerId '9000/tcp' |
                Select-Object -First 1).Trim()
            if ($LASTEXITCODE -ne 0 -or -not $minioPortBinding) {
                throw 'Unable to resolve the MinIO E2E loopback port.'
            }
            $minioApiPort = [int]($minioPortBinding -replace '^.*:', '')
            $minioEndpoint = "http://127.0.0.1:$minioApiPort"
            $env:RENDERWEAVE_ASSET_S3_ENDPOINT = $minioEndpoint
            $env:RENDERWEAVE_ASSET_S3_REGION = $minioRegion
            $env:RENDERWEAVE_ASSET_S3_BUCKET = $minioBucket
            $env:RENDERWEAVE_ASSET_S3_ACCESS_KEY = $minioAccessKey
            $env:RENDERWEAVE_ASSET_S3_SECRET_KEY = $minioSecretKey
            $env:RENDERWEAVE_ASSET_S3_PATH_STYLE = 'true'

            Wait-ForHttp `
                -Uri "$minioEndpoint/minio/health/live" `
                -Name 'local MinIO'
            & docker exec $minioContainerId mc alias set `
                renderweave-local http://127.0.0.1:9000 `
                $minioAccessKey $minioSecretKey *> $null
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to configure the MinIO E2E mc alias.'
            }
            & docker exec $minioContainerId mc mb --ignore-existing `
                "renderweave-local/$minioBucket" *> $null
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to create the MinIO E2E Asset bucket.'
            }
        }
    }

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
    $readinessPath = switch ($Journey) {
        'inference' { 'inference' }
        'template-roundtrip' { 'templates/new' }
        default { 'schemas/new' }
    }
    Wait-ForHttp -Uri "http://127.0.0.1:$webPort/$readinessPath" -Process $webProcess -Name 'RenderWeave web'

    if ($Journey -eq 'template-roundtrip') {
        $npmCommand = Join-Path $nodeDir 'npm.cmd'
        $env:RENDERWEAVE_TEMPLATE_ROUNDTRIP_LIVE = '1'
        $env:RENDERWEAVE_WEB_PORT = "$webPort"
        $env:RENDERWEAVE_EVIDENCE_DIR = $evidenceDir
        $env:RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR = Join-Path $evidenceDir 'pw'
        $env:RENDERWEAVE_PLAYWRIGHT_HTML_DIR = Join-Path $evidenceDir 'pw-report'
        Push-Location $webRoot
        try {
            & $npmCommand run test:e2e -- template-editor-roundtrip-live.spec.ts
        }
        finally {
            Pop-Location
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Template roundtrip browser audit failed with exit code $LASTEXITCODE."
        }
    }
    elseif ($Journey -eq 'inference') {
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
    if ($postgresDataDir) {
        $postgresDataResolved = [System.IO.Path]::GetFullPath($postgresDataDir)
        $tempResolved = [System.IO.Path]::GetFullPath(
            [System.IO.Path]::GetTempPath()
        ).TrimEnd('\')
        $expectedPrefix = $tempResolved + "\renderweave-$Journey-e2e-$PID-postgres"
        if (-not $postgresDataResolved.Equals(
                $expectedPrefix,
                [System.StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Refusing unexpected local PostgreSQL cleanup target: $postgresDataResolved"
        }
        if ($postgresStarted) {
            $pgCtl = Join-Path ([System.IO.Path]::GetFullPath($LocalPostgresBin)) 'pg_ctl.exe'
            & $pgCtl -D $postgresDataResolved stop -m fast *> $null
            if ($LASTEXITCODE -ne 0) {
                $cleanupWarnings += 'local PostgreSQL shutdown failed'
                $postgresShutdownFailed = $true
            }
        }
        if (-not $postgresShutdownFailed -and (Test-Path -LiteralPath $postgresDataResolved)) {
            Remove-Item -LiteralPath $postgresDataResolved -Recurse -Force
        }
    }
    elseif ($containerId) {
        & docker stop $containerName *> $null
    }
    if ($minioContainerId) {
        & docker stop $minioContainerId *> $null
        if ($LASTEXITCODE -ne 0) {
            $remainingMinioContainer = (& docker ps --all --quiet --no-trunc `
                --filter "id=$minioContainerId" 2> $null | Select-Object -First 1)
            if ($LASTEXITCODE -ne 0 -or $remainingMinioContainer) {
                $cleanupWarnings += "owned MinIO container cleanup failed: $minioContainerId"
                $minioContainerShutdownFailed = $true
            }
        }
    }
    foreach ($name in $oldEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
    }
    $postgresCleanupFailure = if ($postgresShutdownFailed) {
        "Local PostgreSQL cleanup failed; data directory retained at $postgresDataResolved."
    }
    else {
        $null
    }
    $minioCleanupFailure = if ($minioContainerShutdownFailed) {
        "Owned MinIO container cleanup failed: $minioContainerId."
    }
    else {
        $null
    }
    $localCleanupFailure = @(
        $postgresCleanupFailure
        $minioCleanupFailure
    ) | Where-Object { $_ }
    $localCleanupFailure = $localCleanupFailure -join ' '
    if ($localCleanupFailure -and $null -eq $journeyFailure) {
        $journeyResult = 'failed'
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
        failure = if ($null -ne $journeyFailure) { $journeyFailure } else { $localCleanupFailure }
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
    if ($localCleanupFailure -and $null -eq $journeyFailure) {
        throw $localCleanupFailure
    }
    if ($localCleanupFailure -and $journeyFailure) {
        Write-Warning "$localCleanupFailure Journey failure is the primary result."
    }
}
