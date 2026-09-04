[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,

    [Parameter(Mandatory = $true)]
    [string]$InputDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'IMAGE_ONLY P1 preflight evidence directory must be below .sdlc/evidence.'
}
$resolvedInputDirectory = (Resolve-Path -LiteralPath $InputDirectory).Path
if (-not (Test-Path -LiteralPath $resolvedInputDirectory -PathType Container)) {
    throw 'IMAGE_ONLY P1 canary input directory is missing.'
}
$inputAttributes = [System.IO.File]::GetAttributes($resolvedInputDirectory)
if (($inputAttributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'IMAGE_ONLY P1 canary input directory cannot be a reparse point.'
}

$cyclePath = Join-Path $repoRoot `
    'plans\image-only-certification-cycles\c3bde304-b0b2-43f8-ab7e-16896ff04aed.json'
$authorizationPath = Join-Path $repoRoot `
    'plans\live-canary-authorizations\20260817-image-only-canary-5-c3bde304.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-p1-preflight-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY P1 preflight summary already exists.'
}

# Clearing credential names is a write-only operation. Do not inspect or display prior values.
@(
    'DASHSCOPE_TOKEN_API_KEY',
    'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

$cycle = Get-Content -Raw -Encoding UTF8 -LiteralPath $cyclePath | ConvertFrom-Json
$authorization = Get-Content -Raw -Encoding UTF8 -LiteralPath $authorizationPath | ConvertFrom-Json
if ($cycle.cycleId -ne $authorization.cycleId -or
        $cycle.manifestIdentity -ne $authorization.manifestIdentity -or
        $cycle.evaluatorIdentity -ne $authorization.evaluatorIdentity -or
        $authorization.status -ne 'OPEN') {
    throw 'IMAGE_ONLY P1 cycle and OPEN authorization identities do not match.'
}

$files = @(Get-ChildItem -LiteralPath $resolvedInputDirectory -File)
if ($files.Count -ne 5) {
    throw "IMAGE_ONLY P1 CANARY_5 requires exactly 5 files; found $($files.Count)."
}

Add-Type -AssemblyName System.Drawing
$actualByHash = @{}
foreach ($file in $files) {
    $stream = [System.IO.File]::OpenRead($file.FullName)
    try {
        $magic = New-Object byte[] 8
        if ($stream.Read($magic, 0, 8) -ne 8 -or
                [System.BitConverter]::ToString($magic) -ne '89-50-4E-47-0D-0A-1A-0A') {
            throw 'IMAGE_ONLY P1 canary input is not a static PNG.'
        }
    }
    finally {
        $stream.Dispose()
    }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
    if ($actualByHash.ContainsKey($hash)) {
        throw 'IMAGE_ONLY P1 canary input hashes must be unique.'
    }
    $image = [System.Drawing.Image]::FromFile($file.FullName)
    try {
        $actualByHash[$hash] = [pscustomobject]@{
            encodedBytes = [int64]$file.Length
            width = [int64]$image.Width
            height = [int64]$image.Height
            pixelCount = [int64]$image.Width * [int64]$image.Height
        }
    }
    finally {
        $image.Dispose()
    }
}

foreach ($case in $cycle.cases) {
    if (-not $actualByHash.ContainsKey($case.artifactSha256)) {
        throw "IMAGE_ONLY P1 canary hash mismatch for case $($case.caseId)."
    }
    $actual = $actualByHash[$case.artifactSha256]
    if ($actual.encodedBytes -ne $case.encodedBytes -or
            $actual.width -ne $case.width -or $actual.height -ne $case.height -or
            $actual.pixelCount -ne $case.pixelCount -or
            $actual.encodedBytes -gt 10MB -or $actual.pixelCount -gt 25000000) {
        throw "IMAGE_ONLY P1 canary metadata mismatch for case $($case.caseId)."
    }
    Write-Host (
        'Validated {0}: sha256={1} bytes={2} dimensions={3}x{4}' -f
        $case.caseId, $case.artifactSha256, $actual.encodedBytes,
        $actual.width, $actual.height
    )
}
if ($actualByHash.Count -ne $cycle.cases.Count) {
    throw 'IMAGE_ONLY P1 canary input contains an unauthorized hash.'
}

$tests = @(
    'ImageOnlyCertificationCanaryAuthorizationTest',
    'ImageOnlyCertificationAuthorizationTest',
    'PostgresCertificationStageExecutionStoreTest',
    'PostgresProfileCertificationStoreTest'
) -join ','
Push-Location $repoRoot
try {
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P1 preflight tests failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

$summary = [ordered]@{
    version = 'renderweave-image-only-certification-p1-preflight/1.0'
    result = 'PASS'
    lifecycle = 'J1_OPEN_PROVIDER_ZERO'
    cycleId = $cycle.cycleId
    stage = $cycle.stage
    profileSha256 = $cycle.profileSha256
    manifestIdentity = $cycle.manifestIdentity
    evaluatorIdentity = $cycle.evaluatorIdentity
    caseCount = $cycle.cases.Count
    maximumRuns = $authorization.maximumRuns
    maximumProviderCalls = $authorization.maximumProviderCalls
    maximumModelTokens = $authorization.maximumModelTokens
    maximumCostMicrosCny = $authorization.maximumCostMicrosCny
    maximumProviderCallsPerRun = $authorization.maximumProviderCallsPerRun
    maximumCostPerRunMicrosCny = $authorization.maximumCostPerRunMicrosCny
    authorizedWindowSeconds = 4 * 60 * 60
    externalProviderUsage = [ordered]@{
        attempts = 0
        reservations = 0
        costMicrosCny = 0
        apiKeyReads = 0
    }
}
$encoding = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText(
    $summaryPath, ($summary | ConvertTo-Json -Depth 6), $encoding)
Write-Host "IMAGE_ONLY P1 Provider-zero preflight: PASS"
Write-Host "Summary: $summaryPath"
