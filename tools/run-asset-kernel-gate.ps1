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
    throw 'Asset kernel evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Asset kernel evidence directory must already exist.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Asset kernel evidence directory escapes .sdlc/evidence.'
}

$primaryReport = Join-Path $resolvedEvidenceDir 'asset-kernel-primary.json'
$independentReport = Join-Path $resolvedEvidenceDir 'asset-kernel-independent.json'
foreach ($report in @($primaryReport, $independentReport)) {
    if (Test-Path -LiteralPath $report) {
        throw "Asset kernel evidence already exists: $report"
    }
}

# This gate is offline and must not read or use provider credentials.
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
    & mvn.cmd -B -ntp -pl renderweave-asset -am `
        "-Drenderweave.asset.primaryReport=$primaryReport" test
    if ($LASTEXITCODE -ne 0) {
        throw "Asset kernel Java primary failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $primaryReport -PathType Leaf)) {
        throw 'Asset kernel Java primary did not write its report.'
    }

    $pythonExe = (& powershell.exe -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $PSScriptRoot 'ensure-python-asset-verify.ps1') | Select-Object -Last 1)
    if ($LASTEXITCODE -ne 0 -or -not $pythonExe) {
        throw 'Unable to provision the pinned Asset verify toolchain.'
    }

    & $pythonExe 'tools\verify-asset-kernel-vectors.py' `
        '--vectors' 'renderweave-asset\src\test\resources\cn\hbads\renderweave\asset\acceptance-kernel-v1\vectors.json' `
        '--primary-report' $primaryReport `
        '--report' $independentReport
    if ($LASTEXITCODE -ne 0) {
        throw "Asset kernel independent replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $independentReport -PathType Leaf)) {
        throw 'Asset kernel independent replay did not write its report.'
    }

    $primary = Get-Content -Raw -Encoding UTF8 -LiteralPath $primaryReport | ConvertFrom-Json
    $independent = Get-Content -Raw -Encoding UTF8 -LiteralPath $independentReport | ConvertFrom-Json
    if ($primary.reportVersion -ne 'renderweave-asset-kernel-primary/1' `
            -or $primary.engine -ne 'java-primary' `
            -or $primary.cases -ne 38 `
            -or $primary.passed -ne 38 `
            -or $primary.failed -ne 0 `
            -or $primary.acceptanceProfileAvailability -ne 'NOT_REGISTERED') {
        throw 'Asset kernel Java primary report boundary drifted.'
    }
    if ($independent.reportVersion -ne 'renderweave-asset-kernel-independent/1' `
            -or $independent.engine -ne 'python-independent' `
            -or $independent.assurance -ne 'A2' `
            -or $independent.cases -ne 38 `
            -or $independent.passed -ne 38 `
            -or $independent.failed -ne 0 `
            -or $independent.acceptanceProfileAvailability -ne 'NOT_REGISTERED' `
            -or $independent.vectorSha256 -ne $primary.vectorSha256) {
        throw 'Asset kernel independent report boundary drifted.'
    }
    Write-Host (
        'Asset kernel: Java={0}/{1} Python={2}/{3} vector=sha256:{4} Profile=NOT_REGISTERED' -f
        $primary.passed, $primary.cases, $independent.passed, $independent.cases,
        $primary.vectorSha256
    )
    Write-Host "Asset kernel evidence: $independentReport"
}
finally {
    Pop-Location
}
