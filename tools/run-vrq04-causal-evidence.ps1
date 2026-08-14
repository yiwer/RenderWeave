[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$candidate = [System.IO.Path]::GetFullPath(
    $(if ([System.IO.Path]::IsPathRooted($EvidenceDir)) { $EvidenceDir } else { Join-Path $repoRoot $EvidenceDir })
)
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $candidate.StartsWith($evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'VRQ-04 evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'VRQ-04 evidence directory must already exist.'
}
$attributes = [System.IO.File]::GetAttributes($candidate)
if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'VRQ-04 evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'VRQ-04 evidence directory escapes .sdlc/evidence.'
}

$reportPath = Join-Path $resolvedEvidenceDir 'rapidocr-shadow-report.json'
$shadowVerifierPath = Join-Path $resolvedEvidenceDir 'rapidocr-shadow-python-summary.json'
$packPath = Join-Path $resolvedEvidenceDir 'vrq04-causal-evidence.json'
$verifierPath = Join-Path $resolvedEvidenceDir 'vrq04-causal-a2.json'
foreach ($path in @($reportPath, $shadowVerifierPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required RapidOCR evidence is missing: $(Split-Path -Leaf $path)"
    }
}
foreach ($path in @($packPath, $verifierPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "VRQ-04 evidence output already exists: $(Split-Path -Leaf $path)"
    }
}

function Invoke-Checked {
    param([string]$Name, [scriptblock]$Action)
    Write-Host "==> $Name"
    $global:LASTEXITCODE = 0
    & $Action
    if (-not $? -or $LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

# This gate consumes local synthetic evidence only. Clear selectors without reading values.
@(
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'DASHSCOPE_TOKEN_API_KEY',
    'DASHSCOPE_TOKEN_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION',
    'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'
$env:RENDERWEAVE_RUN_VRQ04_CAUSAL_PACK = 'true'
$env:RENDERWEAVE_VRQ04_SHADOW_REPORT = $reportPath
$env:RENDERWEAVE_VRQ04_CAUSAL_PACK = $packPath

Push-Location $repoRoot
try {
    Invoke-Checked 'vrq04-independent-verifier-regressions' {
        & python.exe tools/test_offline_quality_resources.py
        if (-not $? -or $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & python.exe tools/test_verify_vrq04_causal_evidence.py
    }
    Invoke-Checked 'vrq04-java-causal-projection' {
        & mvn.cmd -B -ntp -pl renderweave-app -am `
            '-Dtest=RapidOcrCausalEvidencePackTest,RapidOcrCausalEvidenceGateTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
    }
    Invoke-Checked 'vrq04-independent-causal-replay' {
        & python.exe tools/verify_vrq04_causal_evidence.py $reportPath $packPath `
            --repository $repoRoot --output $verifierPath
    }
    foreach ($path in @($packPath, $verifierPath)) {
        $payload = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        foreach ($forbidden in @(
                '"ocrText"', '"ocr_text"', '"imageBytes"', '"promptText"',
                '"providerRequest"', '"providerResponse"', '"modelOutput"',
                '"candidateJson"', '"boundingBox"', '"rootDocument"', '"base64"',
                'data:image', 'ignore prior instructions', 'bearer ')) {
            if ($payload.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw "Payload-safe scan failed for $(Split-Path -Leaf $path)."
            }
        }
    }
}
finally {
    @(
        'RENDERWEAVE_RUN_VRQ04_CAUSAL_PACK',
        'RENDERWEAVE_VRQ04_SHADOW_REPORT',
        'RENDERWEAVE_VRQ04_CAUSAL_PACK'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host "VRQ-04 causal result: PASS; actualAcquisitions=120; ProviderAttempts=0"
Write-Host "VRQ-04 causal evidence: $resolvedEvidenceDir"
