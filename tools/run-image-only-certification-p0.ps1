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
    throw 'IMAGE_ONLY P0 evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidate -PathType Container)) {
    throw 'IMAGE_ONLY P0 evidence directory must already exist.'
}
$attributes = [System.IO.File]::GetAttributes($candidate)
if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'IMAGE_ONLY P0 evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidate).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'IMAGE_ONLY P0 evidence directory escapes .sdlc/evidence.'
}

$reportPath = Join-Path $resolvedEvidenceDir 'image-only-p0-report.json'
$independentPath = Join-Path $resolvedEvidenceDir 'image-only-p0-independent.json'
foreach ($path in @($reportPath, $independentPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "IMAGE_ONLY P0 evidence output already exists: $(Split-Path -Leaf $path)"
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

# P0 is an offline gate. Clear credential names without reading or displaying their values.
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

$tests = @(
    'CertificationAuthorityInventoryTest',
    'ImageOnlyV46ProfileTest',
    'ProfileCertificationServiceTest',
    'ImageOnlyCertificationEvaluatorTest',
    'ImageOnlyCertificationAuthorizationTest',
    'ImageOnlyProductionAdmissionP0GateTest',
    'PostgresProviderBudgetStoreTest',
    'PostgresProfileCertificationStoreTest'
) -join ','

Push-Location $repoRoot
try {
    Invoke-Checked 'p0-python-verifier-contract-tests' {
        & python.exe tools/test_verify_image_only_certification_p0.py
    }

    $env:RENDERWEAVE_RUN_IMAGE_ONLY_P0_GATE = 'true'
    $env:RENDERWEAVE_IMAGE_ONLY_P0_REPORT = $reportPath
    try {
        Invoke-Checked 'p0-java-contract-and-postgresql-tests' {
            & mvn.cmd -B -ntp -pl renderweave-app -am `
                "-Dtest=$tests" `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
    }
    finally {
        [Environment]::SetEnvironmentVariable('RENDERWEAVE_RUN_IMAGE_ONLY_P0_GATE', $null, 'Process')
        [Environment]::SetEnvironmentVariable('RENDERWEAVE_IMAGE_ONLY_P0_REPORT', $null, 'Process')
    }

    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        throw 'IMAGE_ONLY P0 Java gate did not produce its report.'
    }
    Invoke-Checked 'p0-python-independent-replay' {
        & python.exe tools/verify_image_only_certification_p0.py $reportPath `
            --repository $repoRoot --output $independentPath
    }
    if (-not (Test-Path -LiteralPath $independentPath -PathType Leaf)) {
        throw 'IMAGE_ONLY P0 independent replay did not produce its summary.'
    }
}
finally {
    [Environment]::SetEnvironmentVariable('RENDERWEAVE_RUN_IMAGE_ONLY_P0_GATE', $null, 'Process')
    [Environment]::SetEnvironmentVariable('RENDERWEAVE_IMAGE_ONLY_P0_REPORT', $null, 'Process')
    Pop-Location
}

$summary = Get-Content -LiteralPath $independentPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($summary.result -ne 'PASS' -or $summary.providerAttempts -ne 0 `
        -or $summary.providerReservations -ne 0 -or $summary.providerCostMicrosCny -ne 0 `
        -or $summary.apiKeyReads -ne 0 -or $summary.openAuthorizationCount -ne 0) {
    throw 'IMAGE_ONLY P0 independent summary is not Provider-zero and authorization-closed.'
}
Write-Host (
    'IMAGE_ONLY P0: result={0} assurance={1} cases={2} holdout={3} metrics={4} providerAttempts={5}' -f
    $summary.result, $summary.assurance, $summary.caseCount, $summary.holdoutCount,
    $summary.metricCount, $summary.providerAttempts
)
Write-Host "IMAGE_ONLY P0 evidence: $resolvedEvidenceDir"
