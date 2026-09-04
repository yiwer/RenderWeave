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
    throw 'IMAGE_ONLY CANARY_5 live evidence directory must be below .sdlc/evidence.'
}
if (([System.IO.File]::GetAttributes($resolvedEvidenceDir) -band
        [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'IMAGE_ONLY CANARY_5 live evidence directory cannot be a reparse point.'
}

$resolvedInputDirectory = (Resolve-Path -LiteralPath $InputDirectory).Path
if (-not (Test-Path -LiteralPath $resolvedInputDirectory -PathType Container)) {
    throw 'IMAGE_ONLY CANARY_5 input directory is missing.'
}
if (([System.IO.File]::GetAttributes($resolvedInputDirectory) -band
        [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'IMAGE_ONLY CANARY_5 input directory cannot be a reparse point.'
}

$authorizationPath = Join-Path $repoRoot `
    'plans\live-canary-authorizations\20260817-image-only-canary-5-c3bde304.json'
$cyclePath = Join-Path $repoRoot `
    'plans\image-only-certification-cycles\c3bde304-b0b2-43f8-ab7e-16896ff04aed.json'
$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-canary-live-summary.json'
$reviewDirectory = Join-Path $repoRoot `
    '.scratch\image-only-certification-reviews\20260817-iopa-canary5-c3bde304'
$blobDirectory = [System.IO.Path]::GetFullPath((Join-Path $repoRoot `
    'renderweave-app\target\image-only-certification-canary-blobs-c3bde304'))
$blobRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'renderweave-app\target'))
if (-not $blobDirectory.StartsWith(
        $blobRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'IMAGE_ONLY CANARY_5 transient blob directory escaped the module target directory.'
}
foreach ($path in @($summaryPath, $reviewDirectory, $blobDirectory)) {
    if (Test-Path -LiteralPath $path) {
        throw "IMAGE_ONLY CANARY_5 authorization is one-shot; output already exists: $(Split-Path -Leaf $path)"
    }
}

$authorization = Get-Content -Raw -Encoding UTF8 -LiteralPath $authorizationPath |
    ConvertFrom-Json
$cycle = Get-Content -Raw -Encoding UTF8 -LiteralPath $cyclePath | ConvertFrom-Json
$expected = [ordered]@{
    version = 'renderweave-image-only-certification-authorization/1.0'
    authorizationId = '20260817-iopa-canary5-c3bde304'
    cycleId = 'c3bde304-b0b2-43f8-ab7e-16896ff04aed'
    stage = 'CANARY_5'
    profileId = 'dashscope-qwen38-max-product-v46-hybrid-generic'
    profileSha256 = '22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c'
    manifestIdentity = 'renderweave-image-only-certification-manifest/1.0:0e8e93ebaf18b083992aa6110aa895e59219f6b34594e7dceb3d44f129bd5fb4'
    evaluatorIdentity = 'renderweave-image-only-certification-evaluator/1.0:ebdb6bf82083ab35d234d4ded07990848d0e28add6e468c9e5a7b6a90555c29e'
    provider = 'DASHSCOPE'
    model = 'qwen3.8-max'
    providerBaseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
    inputProvenance = 'USER_PROVIDED'
    dataClassification = 'ORDINARY_DESIGN'
    approvedBy = 'RenderWeave owner via conversation exact J1'
    approvalScope = 'IMAGE_ONLY_PROFILE_CERTIFICATION_CANARY_5'
}
foreach ($entry in $expected.GetEnumerator()) {
    if ([string]$authorization.($entry.Key) -cne [string]$entry.Value) {
        throw "IMAGE_ONLY CANARY_5 authorization mismatch: $($entry.Key)."
    }
}
if ($authorization.status -cne 'OPEN' -or
        [int]$authorization.maximumRuns -ne 5 -or
        [int]$authorization.maximumProviderCalls -ne 60 -or
        [int64]$authorization.maximumModelTokens -ne 500000 -or
        [int64]$authorization.maximumCostMicrosCny -ne 10000000 -or
        [int]$authorization.maximumProviderCallsPerRun -ne 12 -or
        [int64]$authorization.maximumCostPerRunMicrosCny -ne 6000000 -or
        $null -ne $authorization.closedAt -or $null -ne $authorization.closureReason) {
    throw 'IMAGE_ONLY CANARY_5 exact J1 caps or OPEN shape do not match.'
}
foreach ($key in @(
        'cycleId', 'stage', 'profileId', 'profileSha256', 'manifestIdentity',
        'evaluatorIdentity', 'inputProvenance', 'dataClassification')) {
    if ([string]$cycle.($key) -cne [string]$expected[$key]) {
        throw "IMAGE_ONLY CANARY_5 frozen cycle mismatch: $key."
    }
}
$effectiveAt = [DateTimeOffset]::Parse([string]$authorization.effectiveAt).ToUniversalTime()
$expiresAt = [DateTimeOffset]::Parse([string]$authorization.expiresAt).ToUniversalTime()
$approvedAt = [DateTimeOffset]::Parse([string]$authorization.approvedAt).ToUniversalTime()
$now = [DateTimeOffset]::UtcNow
if ($effectiveAt -ne [DateTimeOffset]::Parse('2026-08-17T09:48:59Z') -or
        $expiresAt -ne [DateTimeOffset]::Parse('2026-08-17T13:48:59Z') -or
        $approvedAt -ne $effectiveAt -or
        [Math]::Round(($expiresAt - $effectiveAt).TotalSeconds) -ne 14400 -or
        $now -lt $effectiveAt -or $now -ge $expiresAt) {
    throw 'IMAGE_ONLY CANARY_5 exact J1 is not currently effective.'
}

$files = @(Get-ChildItem -LiteralPath $resolvedInputDirectory -File)
if ($files.Count -ne 5) {
    throw "IMAGE_ONLY CANARY_5 requires exactly 5 files; found $($files.Count)."
}
Add-Type -AssemblyName System.Drawing
$actualByHash = @{}
foreach ($file in $files) {
    $stream = [System.IO.File]::OpenRead($file.FullName)
    try {
        $magic = New-Object byte[] 8
        if ($stream.Read($magic, 0, 8) -ne 8 -or
                [System.BitConverter]::ToString($magic) -ne '89-50-4E-47-0D-0A-1A-0A') {
            throw 'IMAGE_ONLY CANARY_5 input is not a static PNG.'
        }
    }
    finally {
        $stream.Dispose()
    }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
    if ($actualByHash.ContainsKey($hash)) {
        throw 'IMAGE_ONLY CANARY_5 input hashes must be unique.'
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
        throw "IMAGE_ONLY CANARY_5 hash mismatch for case $($case.caseId)."
    }
    $actual = $actualByHash[$case.artifactSha256]
    if ($actual.encodedBytes -ne [int64]$case.encodedBytes -or
            $actual.width -ne [int64]$case.width -or
            $actual.height -ne [int64]$case.height -or
            $actual.pixelCount -ne [int64]$case.pixelCount) {
        throw "IMAGE_ONLY CANARY_5 metadata mismatch for case $($case.caseId)."
    }
    Write-Host ('Authorized {0}: sha256={1}' -f $case.caseId, $case.artifactSha256)
}
if ($actualByHash.Count -ne $cycle.cases.Count -or
        $authorization.cases.Count -ne $cycle.cases.Count) {
    throw 'IMAGE_ONLY CANARY_5 input or authorization case cardinality mismatch.'
}
foreach ($case in $authorization.cases) {
    $frozen = @($cycle.cases | Where-Object { $_.caseId -ceq $case.caseId })
    if ($frozen.Count -ne 1 -or
            $frozen[0].artifactSha256 -cne $case.artifactSha256) {
        throw "IMAGE_ONLY CANARY_5 authorization case mismatch: $($case.caseId)."
    }
}

$pythonExecutable = Join-Path $repoRoot `
    '.sdlc\toolchains\document-vision-venv\Scripts\python.exe'
$adapterScript = Join-Path $repoRoot 'tools\document-vision\rapidocr_adapter.py'
$modelRoot = Join-Path $repoRoot `
    '.sdlc\toolchains\document-vision-venv\Lib\site-packages\rapidocr\models'
if (-not (Test-Path -LiteralPath $pythonExecutable -PathType Leaf) -or
        -not (Test-Path -LiteralPath $adapterScript -PathType Leaf) -or
        -not (Test-Path -LiteralPath $modelRoot -PathType Container)) {
    throw 'Frozen RapidOCR/OpenVINO toolchain is unavailable.'
}

function Assert-PayloadSafe {
    param([string]$Payload)
    foreach ($forbidden in @(
            '"rawBytes"', '"normalizedBytes"', '"encodedImage"',
            '"ocrText"', '"promptText"', '"providerRequest"',
            '"providerResponse"', '"modelOutput"', '"candidateJson"',
            '"rootDocument"', '"base64"', 'data:image', 'bearer ')) {
        if ($Payload.IndexOf(
                $forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'IMAGE_ONLY CANARY_5 payload-free evidence guard failed.'
        }
    }
}

$ownedEnvironment = @(
    'RENDERWEAVE_RUN_IMAGE_ONLY_CERTIFICATION_CANARY',
    'RENDERWEAVE_IMAGE_ONLY_CERTIFICATION_INPUT_DIRECTORY',
    'RENDERWEAVE_IMAGE_ONLY_CERTIFICATION_EVIDENCE_DIRECTORY',
    'RENDERWEAVE_DOCUMENT_VISION_ENABLED',
    'RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE',
    'RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT',
    'RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT',
    'RENDERWEAVE_DOCUMENT_VISION_TIMEOUT_SECONDS',
    'RENDERWEAVE_LIVE_AI_ENABLED',
    'RENDERWEAVE_LIVE_UPLOAD_ENABLED',
    'RENDERWEAVE_INFERENCE_RECOVERY_ENABLED'
)
@(
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION',
    'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }

$mavenExit = 1
Push-Location $repoRoot
try {
    $env:RENDERWEAVE_RUN_IMAGE_ONLY_CERTIFICATION_CANARY = 'true'
    $env:RENDERWEAVE_IMAGE_ONLY_CERTIFICATION_INPUT_DIRECTORY = $resolvedInputDirectory
    $env:RENDERWEAVE_IMAGE_ONLY_CERTIFICATION_EVIDENCE_DIRECTORY = $resolvedEvidenceDir
    $env:RENDERWEAVE_DOCUMENT_VISION_ENABLED = 'true'
    $env:RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE = $pythonExecutable
    $env:RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT = $adapterScript
    $env:RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT = $modelRoot
    $env:RENDERWEAVE_DOCUMENT_VISION_TIMEOUT_SECONDS = '60'
    $env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
    $env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'
    $env:RENDERWEAVE_INFERENCE_RECOVERY_ENABLED = 'false'

    Write-Host 'Starting one authorized IMAGE_ONLY CANARY_5 batch; no automatic rerun.'
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        '-Dtest=ImageOnlyCertificationCanaryLiveTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    $mavenExit = $LASTEXITCODE
}
finally {
    foreach ($name in $ownedEnvironment) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Pop-Location
    if (Test-Path -LiteralPath $blobDirectory -PathType Container) {
        $attributes = [System.IO.File]::GetAttributes($blobDirectory)
        if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Refusing to remove a reparse-point transient blob directory.'
        }
        Remove-Item -LiteralPath $blobDirectory -Recurse -Force
    }
}

if (Test-Path -LiteralPath $summaryPath -PathType Leaf) {
    $summaryRaw = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath
    Assert-PayloadSafe -Payload $summaryRaw
    $summary = $summaryRaw | ConvertFrom-Json
    if ($summary.version -cne 'renderweave-image-only-certification-canary-live/1.0' -or
            $summary.authorizationId -cne $expected.authorizationId -or
            $summary.cycleId -cne $expected.cycleId -or
            $summary.profileId -cne $expected.profileId -or
            [bool]$summary.candidateApplied -or [bool]$summary.staticSchemaPublished -or
            $summary.ledger.status -cne 'CLOSED' -or
            [int]$summary.maximumProviderCallsPerRun -ne 12 -or
            [int64]$summary.maximumCostPerRunMicrosCny -ne 6000000) {
        throw 'IMAGE_ONLY CANARY_5 live summary contract is invalid.'
    }
}

if ($mavenExit -ne 0) {
    if ((Test-Path -LiteralPath $reviewDirectory -PathType Container) -or
            (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw "IMAGE_ONLY CANARY_5 live batch terminated with exit code $mavenExit; automatic rerun is forbidden."
    }
    throw "IMAGE_ONLY CANARY_5 preflight terminated with exit code $mavenExit before its one-shot marker."
}
if (-not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
    throw 'IMAGE_ONLY CANARY_5 live batch completed without its payload-free summary.'
}
Write-Host "IMAGE_ONLY CANARY_5 provider batch closed; summary: $summaryPath"
