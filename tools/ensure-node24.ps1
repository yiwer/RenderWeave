[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$version = '24.19.0'
$archiveName = "node-v$version-win-x64.zip"
$expectedSha256 = '57f71ab3652e797d84acddc79c81cc9ff1c6ddb2a1974cdb83f00fee9bff4c73'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$downloadDir = Join-Path $repoRoot '.sdlc\downloads'
$toolchainsDir = Join-Path $repoRoot '.sdlc\toolchains'
$nodeDir = Join-Path $toolchainsDir "node-v$version-win-x64"
$nodeExe = Join-Path $nodeDir 'node.exe'

if (-not (Test-Path -LiteralPath $nodeExe)) {
    $null = New-Item -ItemType Directory -Path $downloadDir -Force
    $null = New-Item -ItemType Directory -Path $toolchainsDir -Force
    $archivePath = Join-Path $downloadDir $archiveName

    if (-not (Test-Path -LiteralPath $archivePath)) {
        Invoke-WebRequest `
            -Uri "https://nodejs.org/dist/v$version/$archiveName" `
            -OutFile $archivePath `
            -UseBasicParsing
    }

    $actualSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "Node archive checksum mismatch. Expected $expectedSha256, got $actualSha256."
    }

    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolchainsDir -Force
}

$actualVersion = (& $nodeExe --version).TrimStart('v')
if ($actualVersion -ne $version) {
    throw "Expected Node $version, got $actualVersion at $nodeExe."
}

Write-Output $nodeDir

