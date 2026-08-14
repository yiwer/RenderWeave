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
    throw 'VRQ-07 evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'VRQ-07 evidence directory must already exist.'
}
$attributes = [System.IO.File]::GetAttributes($candidate)
if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'VRQ-07 evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'VRQ-07 evidence directory escapes .sdlc/evidence.'
}

$rapidPath = Join-Path $resolvedEvidenceDir 'vrq04-causal-evidence.json'
$r3Path = Join-Path $resolvedEvidenceDir 'vrq05-r3-probe-evidence.json'
$r5Path = Join-Path $resolvedEvidenceDir 'vrq06-r5-oracle-evidence.json'
$packPath = Join-Path $resolvedEvidenceDir 'vrq07-evidence-pack.json'
$decisionPath = Join-Path $resolvedEvidenceDir 'vrq07-decision.json'
$verifierPath = Join-Path $resolvedEvidenceDir 'vrq07-decision-a2.json'
foreach ($path in @($rapidPath, $r3Path, $r5Path)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required offline evidence is missing: $(Split-Path -Leaf $path)"
    }
}
foreach ($path in @($packPath, $decisionPath, $verifierPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "VRQ-07 evidence output already exists: $(Split-Path -Leaf $path)"
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
$env:RENDERWEAVE_RUN_VRQ07_OFFLINE_DECISION = 'true'
$env:RENDERWEAVE_VRQ07_RAPIDOCR_CAUSAL = $rapidPath
$env:RENDERWEAVE_VRQ07_R3_EVIDENCE = $r3Path
$env:RENDERWEAVE_VRQ07_R5_EVIDENCE = $r5Path
$env:RENDERWEAVE_VRQ07_EVIDENCE_PACK = $packPath
$env:RENDERWEAVE_VRQ07_DECISION = $decisionPath

Push-Location $repoRoot
try {
    Invoke-Checked 'vrq07-java-single-decision-seam' {
        & mvn.cmd -B -ntp -pl renderweave-app -am `
            '-Dtest=OfflineQualityDecisionAssemblerTest,R2R5TriggerDecisionEngineTest,OfflineQualityDecisionGateTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
    }
    Invoke-Checked 'vrq07-independent-decision-reconstruction' {
        & python.exe tools/verify_vrq07_offline_decision.py `
            --rapidocr $rapidPath --r3 $r3Path --r5 $r5Path `
            --pack $packPath --decision $decisionPath `
            --repository $repoRoot --output $verifierPath
    }
    foreach ($path in @($packPath, $decisionPath, $verifierPath)) {
        $payload = Get-Content -LiteralPath $path -Raw -Encoding UTF8
        foreach ($forbidden in @(
                '"ocrText"', '"ocr_text"', '"imageBytes"', '"promptText"',
                '"providerRequest"', '"providerResponse"', '"modelOutput"',
                '"candidateJson"', '"boundingBox"', '"rootDocument"', '"base64"',
                '"inspectionRequest"', 'data:image', 'ignore prior instructions', 'bearer ')) {
            if ($payload.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw "Payload-safe scan failed for $(Split-Path -Leaf $path)."
            }
        }
    }
}
finally {
    @(
        'RENDERWEAVE_RUN_VRQ07_OFFLINE_DECISION',
        'RENDERWEAVE_VRQ07_RAPIDOCR_CAUSAL',
        'RENDERWEAVE_VRQ07_R3_EVIDENCE',
        'RENDERWEAVE_VRQ07_R5_EVIDENCE',
        'RENDERWEAVE_VRQ07_EVIDENCE_PACK',
        'RENDERWEAVE_VRQ07_DECISION'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host 'VRQ-07 result: STOP_TO_SPEC_R5; ProviderAttempts=0'
Write-Host "VRQ-07 evidence: $resolvedEvidenceDir"
