[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$candidateEvidenceDir = [System.IO.Path]::GetFullPath(
    $(if ([System.IO.Path]::IsPathRooted($EvidenceDir)) {
            $EvidenceDir
        }
        else {
            Join-Path $repoRoot $EvidenceDir
        })
)
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $candidateEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Design/Input/Expression class evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Design/Input/Expression class evidence directory must already exist.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Design/Input/Expression class evidence directory escapes .sdlc/evidence.'
}

$authorityRoot = Join-Path $repoRoot '.scratch\renderweave-template-v1\design-input-expression'
$target = Join-Path $authorityRoot 'execution-class-target-v1.json'
$javaExecutor = Join-Path $authorityRoot 'java-semantic-authority-executor-manifest-v1.json'
$typescriptExecutor = Join-Path $authorityRoot `
    'typescript-independent-authoring-replayer-manifest-v1.json'
$capacityPrimary = Join-Path $resolvedEvidenceDir `
    'design-input-expression-capacity-primary.json'
$capacityIndependent = Join-Path $resolvedEvidenceDir `
    'design-input-expression-capacity-independent.json'
$classReport = Join-Path $resolvedEvidenceDir `
    'design-input-expression-execution-class-independent.json'
foreach ($path in @($target, $javaExecutor, $typescriptExecutor)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Design/Input/Expression class authority is missing: $path"
    }
}
foreach ($path in @($capacityPrimary, $capacityIndependent, $classReport)) {
    if (Test-Path -LiteralPath $path) {
        throw "Design/Input/Expression class evidence already exists: $path"
    }
}

@(
    'DASHSCOPE_TOKEN_API_KEY',
    'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION',
    'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

Push-Location $repoRoot
try {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $PSScriptRoot 'run-design-input-expression-capacity-gate.ps1') `
        -EvidenceDir $resolvedEvidenceDir
    if ($LASTEXITCODE -ne 0) {
        throw "Design/Input/Expression component gate failed with exit code $LASTEXITCODE."
    }
    foreach ($path in @($capacityPrimary, $capacityIndependent)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Design/Input/Expression component replay did not produce: $path"
        }
    }

    $pythonVersion = (& py.exe -3.13 --version 2>&1).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $pythonVersion.StartsWith('Python 3.13.')) {
        throw "Python 3.13 is required for independent closure replay; got $pythonVersion."
    }
    & py.exe -3.13 'tools\verify-design-input-expression-execution-class.py' `
        '--repo' $repoRoot `
        '--target' $target `
        '--java-executor' $javaExecutor `
        '--typescript-executor' $typescriptExecutor `
        '--capacity-primary' $capacityPrimary `
        '--capacity-independent' $capacityIndependent `
        '--report' $classReport
    if ($LASTEXITCODE -ne 0) {
        throw "Design/Input/Expression independent closure replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $classReport -PathType Leaf)) {
        throw 'Design/Input/Expression independent closure replay did not write its report.'
    }

    $result = Get-Content -Raw -Encoding UTF8 -LiteralPath $classReport |
        ConvertFrom-Json
    if ($result.reportVersion -ne `
            'renderweave-design-input-expression-execution-class-independent/1' `
            -or $result.assurance -ne 'A2_PREISSUANCE_PRODUCT_REPLAY' `
            -or $result.executorRoleCount -ne 2 `
            -or $result.capacityCaseCount -ne 195 `
            -or $result.boundary.formalCaseCount -ne 58 `
            -or $result.boundary.formalOracleCount -ne 58 `
            -or $result.boundary.formalRecordsIssued -ne 0 `
            -or -not $result.boundary.preissuanceReady `
            -or -not $result.boundary.recordAppendMayProceedInSeparateTicket `
            -or $result.boundary.executionClassExecutable `
            -or $result.boundary.externalProviderAttempts -ne 0) {
        throw 'Design/Input/Expression execution-class report boundary drifted.'
    }
    Write-Host 'DESIGN_INPUT_EXPRESSION class gate: 2/2 roles, 195/195 capacity PASS'
}
finally {
    Pop-Location
}
