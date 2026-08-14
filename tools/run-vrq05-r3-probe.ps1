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
    throw 'VRQ-05 evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'VRQ-05 evidence directory must already exist.'
}
$attributes = [System.IO.File]::GetAttributes($candidate)
if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'VRQ-05 evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'VRQ-05 evidence directory escapes .sdlc/evidence.'
}

$reportPath = Join-Path $resolvedEvidenceDir 'rapidocr-shadow-report.json'
$evidencePath = Join-Path $resolvedEvidenceDir 'vrq05-r3-probe-evidence.json'
$verifierPath = Join-Path $resolvedEvidenceDir 'vrq05-r3-probe-a2.json'
if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
    throw 'Required RapidOCR evidence is missing: rapidocr-shadow-report.json'
}
foreach ($path in @($evidencePath, $verifierPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "VRQ-05 evidence output already exists: $(Split-Path -Leaf $path)"
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
$env:RENDERWEAVE_RUN_VRQ05_R3_PROBE = 'true'
$env:RENDERWEAVE_VRQ05_RAPIDOCR_REPORT = $reportPath
$env:RENDERWEAVE_VRQ05_R3_EVIDENCE = $evidencePath

Push-Location $repoRoot
try {
    Invoke-Checked 'vrq05-independent-verifier-regressions' {
        & python.exe tools/test_offline_quality_resources.py
        if (-not $? -or $LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & python.exe tools/test_verify_vrq05_r3_probe.py
    }
    Invoke-Checked 'vrq05-java-r3-probe' {
        & mvn.cmd -B -ntp -pl renderweave-app -am `
            '-Dtest=R3OrderRepeatProbeEvidenceTest,R3OrderRepeatProbeGateTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
    }
    Invoke-Checked 'vrq05-independent-r3-replay' {
        & python.exe tools/verify_vrq05_r3_probe.py $reportPath $evidencePath `
            --repository $repoRoot --output $verifierPath
    }
    foreach ($path in @($evidencePath, $verifierPath)) {
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
        'RENDERWEAVE_RUN_VRQ05_R3_PROBE',
        'RENDERWEAVE_VRQ05_RAPIDOCR_REPORT',
        'RENDERWEAVE_VRQ05_R3_EVIDENCE'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host 'VRQ-05 R3 result: MISSING; trigger=false; ProviderAttempts=0'
Write-Host "VRQ-05 R3 evidence: $resolvedEvidenceDir"
