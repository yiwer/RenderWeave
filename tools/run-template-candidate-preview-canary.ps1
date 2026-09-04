[CmdletBinding()]
param(
    [string]$EvidenceDir,
    [switch]$SkipBuild,
    [string]$RendererVolume = 'rw-t217-final-20260901-b',
    [string]$RendererBuildImage =
        'sha256:dff7bf7639ce459600e6e042228480eb9b6c627ce590e282c9b1d7c03fcad30b',
    [string]$JreImage = 'eclipse-temurin:21-jre',
    [string]$WebImage = 'nginx:1.29-alpine',
    [string]$PostgresImage = 'postgres:16-alpine'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$webRoot = Join-Path $repoRoot 'web'
$jarPath = Join-Path $repoRoot 'renderweave-app\target\renderweave-app-1.0-SNAPSHOT.jar'
$manifestPath = Join-Path $repoRoot 'renderer\process-manifest.json'
$nginxConfig = Join-Path $PSScriptRoot 'template-candidate-preview-nginx.conf'
$expectedRendererSha256 = '55cb098ff1022c6e3c94e940c4926be8fc6feddac29ca39376dd52e9f5bd392b'
$expectedManifestSha256 = '7ff8353272715dfdb911c0354b04d33b86f203b8c0a4bd4c1d5762e524e32734'
$rendererExecutable = '/candidate/t214/target/release/renderweave-renderer-daemon'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runId = "$PID-$((New-Guid).ToString('N').Substring(0, 8))"
$resourcePrefix = "rw-template-candidate-$runId"
$networkName = "$resourcePrefix-net"
$postgresName = "$resourcePrefix-postgres"
$appName = "$resourcePrefix-app"
$webName = "$resourcePrefix-web"
$evidenceDir = if ($EvidenceDir) {
    [System.IO.Path]::GetFullPath($EvidenceDir)
}
else {
    Join-Path $repoRoot ".sdlc\evidence\$timestamp-template-candidate-preview"
}
$null = New-Item -ItemType Directory -Path $evidenceDir -Force
$startedAt = Get-Date
$result = 'failed'
$failure = $null
$cleanupWarnings = @()
$apiPort = $null
$webPort = $null
$rendererSha256 = $null
$manifestSha256 = $null
$revision = (& git -C $repoRoot rev-parse --verify HEAD).Trim()
$workingTreeDirty = [bool]((& git -C $repoRoot status --porcelain=v1 2>&1) -join "`n")

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Get-FreeTcpPort {
    $listener = New-Object System.Net.Sockets.TcpListener(
        [System.Net.IPAddress]::Loopback,
        0
    )
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Wait-ForHttp {
    param([string]$Uri, [string]$Container, [string]$Name)
    foreach ($attempt in 1..180) {
        $running = (& docker inspect $Container --format '{{.State.Running}}' 2>$null)
        if ($LASTEXITCODE -ne 0 -or $running -ne 'true') {
            throw "$Name container exited before becoming ready."
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
    throw "$Name did not become ready within 45 seconds."
}

function Capture-ContainerLog {
    param([string]$Container, [string]$Filename)
    $exists = (& docker ps -a --filter "name=^/$Container$" --format '{{.Names}}')
    if ($LASTEXITCODE -eq 0 -and $exists -eq $Container) {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $lines = @(& docker logs $Container 2>&1)
            $lines | Out-File `
                -LiteralPath (Join-Path $evidenceDir $Filename) `
                -Encoding utf8
        }
        finally {
            $ErrorActionPreference = $previousErrorAction
        }
    }
}

try {
    foreach ($path in @($manifestPath, $nginxConfig)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Required canary input is missing: $path"
        }
    }
    & docker volume inspect $RendererVolume *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Exact T217 Renderer volume is unavailable: $RendererVolume"
    }
    & docker image inspect $RendererBuildImage *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Exact T217 build image is unavailable: $RendererBuildImage"
    }

    $rendererHashLine = (& docker run --rm `
        --volume "${RendererVolume}:/candidate:ro" `
        $RendererBuildImage `
        sha256sum $rendererExecutable)
    if ($LASTEXITCODE -ne 0 -or -not $rendererHashLine) {
        throw 'Unable to hash the exact native Renderer candidate.'
    }
    $rendererSha256 = ($rendererHashLine -split '\s+')[0].ToLowerInvariant()
    $manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($rendererSha256 -ne $expectedRendererSha256) {
        throw "Renderer candidate hash mismatch: $rendererSha256"
    }
    if ($manifestSha256 -ne $expectedManifestSha256) {
        throw "Renderer process manifest hash mismatch: $manifestSha256"
    }

    $nodeDir = (& powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $PSScriptRoot 'ensure-node24.ps1') | Select-Object -Last 1)
    if ($LASTEXITCODE -ne 0 -or -not $nodeDir) {
        throw 'Unable to provision the pinned Node 24 toolchain.'
    }
    $npmCommand = Join-Path $nodeDir 'npm.cmd'
    if (-not $SkipBuild) {
        & mvn.cmd -B -ntp -pl renderweave-app -am '-DskipTests' package
        if ($LASTEXITCODE -ne 0) {
            throw "Application package failed with exit code $LASTEXITCODE."
        }
        Push-Location $webRoot
        try {
            if (-not (Test-Path -LiteralPath (Join-Path $webRoot 'node_modules'))) {
                & $npmCommand ci --no-audit --no-fund
                if ($LASTEXITCODE -ne 0) {
                    throw "Web dependency install failed with exit code $LASTEXITCODE."
                }
            }
            & $npmCommand run build
            if ($LASTEXITCODE -ne 0) {
                throw "Web build failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
    }
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "Application jar is missing: $jarPath"
    }
    $distPath = Join-Path $webRoot 'dist'
    if (-not (Test-Path -LiteralPath (Join-Path $distPath 'index.html') -PathType Leaf)) {
        throw "Web dist is missing: $distPath"
    }

    $apiPort = Get-FreeTcpPort
    $webPort = Get-FreeTcpPort
    & docker network create $networkName *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to create the ephemeral Candidate Preview network.'
    }

    $postgresId = (& docker run --detach --rm `
        --name $postgresName `
        --network $networkName `
        --env POSTGRES_DB=renderweave `
        --env POSTGRES_USER=renderweave `
        --env POSTGRES_PASSWORD=renderweave-candidate-local `
        $PostgresImage).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $postgresId) {
        throw 'Unable to start ephemeral PostgreSQL.'
    }
    $databaseReady = $false
    foreach ($attempt in 1..120) {
        & docker exec $postgresName pg_isready -U renderweave -d renderweave *> $null
        if ($LASTEXITCODE -eq 0) {
            $databaseReady = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $databaseReady) {
        throw 'Ephemeral PostgreSQL did not become ready within 30 seconds.'
    }

    $appId = (& docker run --detach --rm `
        --name $appName `
        --network $networkName `
        --publish "127.0.0.1:${apiPort}:8080" `
        --publish "127.0.0.1:${webPort}:4173" `
        --tmpfs '/run/renderweave:rw,nosuid,nodev,noexec,size=16m' `
        --volume "${jarPath}:/app/app.jar:ro" `
        --volume "${manifestPath}:/app/process-manifest.json:ro" `
        --volume "${RendererVolume}:/candidate:ro" `
        --env "RENDERWEAVE_DB_URL=jdbc:postgresql://${postgresName}:5432/renderweave" `
        --env RENDERWEAVE_DB_USERNAME=renderweave `
        --env RENDERWEAVE_DB_PASSWORD=renderweave-candidate-local `
        --env RENDERWEAVE_DB_MIN_IDLE=1 `
        --env RENDERWEAVE_TEMPLATE_SINGLE_OWNER_ENABLED=true `
        --env RENDERWEAVE_TEMPLATE_SINGLE_OWNER_OWNER_SCOPE=candidate-local `
        --env 'RENDERWEAVE_TEMPLATE_SINGLE_OWNER_CAPABILITIES=template.create,template.read,template.update,template.render' `
        --env RENDERWEAVE_TEMPLATE_CANDIDATE_PREVIEW_ENABLED=true `
        --env RENDERWEAVE_RENDERING_ENGINE_PROCESS_ENABLED=true `
        --env "RENDERWEAVE_RENDERING_ENGINE_PROCESS_EXECUTABLE=$rendererExecutable" `
        --env RENDERWEAVE_RENDERING_ENGINE_PROCESS_SOCKET=/run/renderweave/renderer.sock `
        --env RENDERWEAVE_RENDERING_ENGINE_PROCESS_MANIFEST=/app/process-manifest.json `
        --env "RENDERWEAVE_RENDERING_ENGINE_PROCESS_MANIFEST_SHA256=sha256:$manifestSha256" `
        --env RENDERWEAVE_ASSET_FETCH_BASE_URL=https://render.internal.example `
        --env RENDERWEAVE_RENDERING_ENGINE_PROCESS_ASSET_FETCH_ALLOWED_IPS=127.0.0.1 `
        --env RENDERWEAVE_RENDERING_ENGINE_PROCESS_MAX_FRAME_BYTES=16777216 `
        --env RENDERWEAVE_RENDERING_ENGINE_PROCESS_STARTUP_TIMEOUT_MS=10000 `
        --env RENDERWEAVE_RENDERING_ENGINE_PROCESS_RESTART_BACKOFF_MS=250 `
        --env RENDERWEAVE_RENDERING_ENGINE_PROCESS_HANDSHAKE_TIMEOUT_MS=5000 `
        --env RENDERWEAVE_BLOB_ROOT=/tmp/renderweave-blobs `
        --env RENDERWEAVE_LIVE_AI_ENABLED=false `
        --env RENDERWEAVE_LIVE_UPLOAD_ENABLED=false `
        $JreImage `
        bash -lc 'umask 077 && chmod 0700 /run/renderweave && mkdir -p /tmp/renderweave-blobs && exec java -jar /app/app.jar').Trim()
    if ($LASTEXITCODE -ne 0 -or -not $appId) {
        throw 'Unable to start the Candidate Preview app/Renderer container.'
    }
    Wait-ForHttp `
        -Uri "http://127.0.0.1:$apiPort/api/v1/system/status" `
        -Container $appName `
        -Name 'RenderWeave Candidate API'

    $webId = (& docker run --detach --rm `
        --name $webName `
        --network "container:$appName" `
        --volume "${distPath}:/usr/share/nginx/html:ro" `
        --volume "${nginxConfig}:/etc/nginx/conf.d/default.conf:ro" `
        $WebImage).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $webId) {
        throw 'Unable to start the loopback Web sidecar.'
    }
    Wait-ForHttp `
        -Uri "http://127.0.0.1:$webPort/templates/new" `
        -Container $webName `
        -Name 'RenderWeave Candidate Web'

    $oldEnvironment = @{}
    foreach ($name in @(
        'RENDERWEAVE_TEMPLATE_CANDIDATE_LIVE',
        'RENDERWEAVE_WEB_PORT',
        'RENDERWEAVE_EVIDENCE_DIR',
        'RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR',
        'RENDERWEAVE_PLAYWRIGHT_HTML_DIR',
        'NO_COLOR'
    )) {
        $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        $env:RENDERWEAVE_TEMPLATE_CANDIDATE_LIVE = '1'
        $env:RENDERWEAVE_WEB_PORT = "$webPort"
        $env:RENDERWEAVE_EVIDENCE_DIR = $evidenceDir
        $env:RENDERWEAVE_PLAYWRIGHT_OUTPUT_DIR = Join-Path $evidenceDir 'pw'
        $env:RENDERWEAVE_PLAYWRIGHT_HTML_DIR = Join-Path $evidenceDir 'pw-report'
        Remove-Item Env:NO_COLOR -ErrorAction SilentlyContinue
        Push-Location $webRoot
        try {
            $previousErrorAction = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                & $npmCommand --loglevel=error run test:e2e -- `
                    template-candidate-preview-live.spec.ts `
                    --trace=off 2>&1 | Tee-Object -FilePath (Join-Path $evidenceDir 'playwright.log')
                $playwrightExit = $LASTEXITCODE
            }
            finally {
                $ErrorActionPreference = $previousErrorAction
            }
        }
        finally {
            Pop-Location
        }
        if ($playwrightExit -ne 0) {
            throw "Candidate Preview browser canary failed with exit code $playwrightExit."
        }
    }
    finally {
        foreach ($name in $oldEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process')
        }
    }
    if (-not (Test-Path -LiteralPath `
            (Join-Path $evidenceDir 'candidate-preview-summary.json') -PathType Leaf)) {
        throw 'Browser canary passed without its payload-free validation summary.'
    }
    $result = 'passed'
}
catch {
    $failure = $_.Exception.Message
}
finally {
    Capture-ContainerLog -Container $webName -Filename 'web.log'
    Capture-ContainerLog -Container $appName -Filename 'app.log'
    Capture-ContainerLog -Container $postgresName -Filename 'postgres.log'

    foreach ($container in @($webName, $appName, $postgresName)) {
        $exists = (& docker ps -a --filter "name=^/$container$" --format '{{.Names}}')
        if ($LASTEXITCODE -eq 0 -and $exists -eq $container) {
            & docker rm --force $container *> $null
            if ($LASTEXITCODE -ne 0) {
                $cleanupWarnings += "container cleanup failed: $container"
            }
        }
    }
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker network inspect $networkName *> $null
        $networkExists = $LASTEXITCODE -eq 0
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($networkExists) {
        & docker network rm $networkName *> $null
        if ($LASTEXITCODE -ne 0) {
            $cleanupWarnings += "network cleanup failed: $networkName"
        }
    }
    $remainingContainers = @(& docker ps -a `
        --filter "name=^/$resourcePrefix" `
        --format '{{.Names}}')
    $networkRemaining = (& docker network ls `
        --filter "name=^${networkName}$" `
        --format '{{.Name}}')
    $cleanupVerified = -not $remainingContainers -and -not $networkRemaining
    if (-not $cleanupVerified) {
        $cleanupWarnings += 'ephemeral Docker resources remain after cleanup'
    }

    $metadata = [ordered]@{
        contractVersion = 'renderweave-template-candidate-preview-canary/1.0'
        result = $result
        assurance = 'NOT_CERTIFIED'
        capturedBy = 'tools/run-template-candidate-preview-canary.ps1'
        revision = $revision
        workingTreeDirty = $workingTreeDirty
        startedAt = $startedAt.ToString('o')
        finishedAt = (Get-Date).ToString('o')
        apiPort = $apiPort
        webPort = $webPort
        rendererVolume = $RendererVolume
        rendererBuildImage = $RendererBuildImage
        rendererExecutableSha256 = $rendererSha256
        processManifestSha256 = $manifestSha256
        candidateStatus = 'NOT_CERTIFIED'
        formalCertificationIssued = $false
        externalModelCallsAllowed = $false
        cleanupVerified = $cleanupVerified
        cleanupWarning = $cleanupWarnings -join '; '
        failure = $failure
    }
    Write-Utf8File `
        -Path (Join-Path $evidenceDir 'metadata.json') `
        -Content ($metadata | ConvertTo-Json -Depth 5)
    $artifactRows = foreach ($file in Get-ChildItem -LiteralPath $evidenceDir -Recurse -File |
            Sort-Object FullName) {
        if ($file.Name -eq 'artifact-manifest.sha256') {
            continue
        }
        $relative = $file.FullName.Substring($evidenceDir.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
    Write-Utf8File `
        -Path (Join-Path $evidenceDir 'artifact-manifest.sha256') `
        -Content ($artifactRows -join "`n")
}

Write-Host "`nEvidence: $evidenceDir"
if ($failure) {
    Write-Error $failure
    exit 1
}
if ($cleanupWarnings) {
    Write-Error ($cleanupWarnings -join '; ')
    exit 1
}
