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
    throw 'R5 product-transform evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'R5 product-transform evidence directory must already exist.'
}
$attributes = [System.IO.File]::GetAttributes($candidate)
if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'R5 product-transform evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'R5 product-transform evidence directory escapes .sdlc/evidence.'
}

$evidencePath = Join-Path $resolvedEvidenceDir 'r5-product-transform-evidence.json'
$verifierPath = Join-Path $resolvedEvidenceDir 'r5-product-transform-a2.json'
foreach ($path in @($evidencePath, $verifierPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "R5 product-transform evidence output already exists: $(Split-Path -Leaf $path)"
    }
}

$pythonExecutable = Join-Path $repoRoot '.sdlc\toolchains\document-vision-venv\Scripts\python.exe'
$adapterScript = Join-Path $repoRoot 'tools\document-vision\rapidocr_adapter.py'
$modelRoot = Join-Path $repoRoot '.sdlc\toolchains\document-vision-venv\Lib\site-packages\rapidocr\models'
if (-not (Test-Path -LiteralPath $pythonExecutable -PathType Leaf) `
        -or -not (Test-Path -LiteralPath $adapterScript -PathType Leaf) `
        -or -not (Test-Path -LiteralPath $modelRoot -PathType Container)) {
    throw 'Frozen RapidOCR/OpenVINO toolchain is unavailable.'
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
$env:RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE = $pythonExecutable
$env:RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT = $adapterScript
$env:RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT = $modelRoot
$env:RENDERWEAVE_RUN_R5_PRODUCT_TRANSFORM = 'true'
$env:RENDERWEAVE_R5_PRODUCT_TRANSFORM_EVIDENCE = $evidencePath

Push-Location $repoRoot
try {
    Invoke-Checked 'r5-product-transform-independent-verifier-regressions' {
        & python.exe tools/test_verify_r5_product_transform.py
    }
    Invoke-Checked 'r5-product-transform-adapter-contract-tests' {
        & $pythonExecutable tools/document-vision/test_rapidocr_adapter.py
    }
    Invoke-Checked 'r5-product-transform-two-run-actual-evaluation' {
        & mvn.cmd -B -ntp -pl renderweave-app -am `
            '-Dtest=R5ProductRasterTransformTest,R5ProductTransformEvaluationTest,R5ProductTransformEvidenceTest,R5ProductTransformGateTest' `
            '-Dsurefire.failIfNoSpecifiedTests=false' test
    }
    if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf)) {
        throw 'Actual R5 product-transform evaluation did not produce canonical evidence.'
    }
    Invoke-Checked 'r5-product-transform-independent-a2' {
        & python.exe tools/verify_r5_product_transform.py $evidencePath `
            --repository $repoRoot --output $verifierPath
    }
    foreach ($path in @($evidencePath, $verifierPath)) {
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
        'RENDERWEAVE_RUN_R5_PRODUCT_TRANSFORM',
        'RENDERWEAVE_R5_PRODUCT_TRANSFORM_EVIDENCE'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

$summary = Get-Content -Raw -Encoding UTF8 -LiteralPath $verifierPath | ConvertFrom-Json
Write-Host "R5 product-transform result: $($summary.disposition); qualified=$($summary.qualified); ProviderAttempts=0"
Write-Host "R5 product-transform evidence: $resolvedEvidenceDir"
if (-not $summary.qualified) {
    throw 'R5_PRODUCT_TRANSFORM_NOT_QUALIFIED'
}
