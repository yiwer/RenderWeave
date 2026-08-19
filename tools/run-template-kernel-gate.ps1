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
    throw 'Template kernel evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Template kernel evidence directory must already exist.'
}
$evidenceAttributes = [System.IO.File]::GetAttributes($candidateEvidenceDir)
if (($evidenceAttributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Template kernel evidence directory cannot be a reparse point.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Template kernel evidence directory escapes .sdlc/evidence.'
}

$primaryReport = Join-Path $resolvedEvidenceDir 'template-kernel-primary.json'
$independentReport = Join-Path $resolvedEvidenceDir 'template-kernel-independent.json'
$assetRefPrimaryReport = Join-Path $resolvedEvidenceDir 'template-asset-ref-primary.json'
$assetRefIndependentReport = Join-Path $resolvedEvidenceDir 'template-asset-ref-independent.json'
foreach ($report in @($primaryReport, $independentReport, $assetRefPrimaryReport, $assetRefIndependentReport)) {
    if (Test-Path -LiteralPath $report) {
        throw "Template kernel evidence already exists: $report"
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
    & mvn.cmd -B -ntp -pl renderweave-template -am `
        "-Drenderweave.template.primaryReport=$primaryReport" test
    if ($LASTEXITCODE -ne 0) {
        throw "Template kernel Java primary failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $primaryReport -PathType Leaf)) {
        throw 'Template kernel Java primary did not write its report.'
    }

    & python.exe 'tools\verify-template-kernel-vectors.py' `
        '--vectors' 'renderweave-template\src\test\resources\cn\hbads\renderweave\template\canonical-kernel-v1\vectors.json' `
        '--primary-report' $primaryReport `
        '--report' $independentReport
    if ($LASTEXITCODE -ne 0) {
        throw "Template kernel independent replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $independentReport -PathType Leaf)) {
        throw 'Template kernel independent replay did not write its report.'
    }

    # T20 dependency-projection extraction: Java primary over the fixture corpus, then the
    # independent Python re-extraction (A2) over the same fixtures.
    & mvn.cmd -B -ntp -pl renderweave-template -am `
        "-Dtest=AssetRefAtomExtractionTest" `
        "-Drenderweave.template.assetRefReport=$assetRefPrimaryReport" `
        "-Dsurefire.failIfNoSpecifiedTests=false" test
    if ($LASTEXITCODE -ne 0) {
        throw "Template asset-ref extraction Java primary failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $assetRefPrimaryReport -PathType Leaf)) {
        throw 'Template asset-ref extraction Java primary did not write its report.'
    }

    & python.exe 'tools\verify-template-asset-ref-extraction.py' `
        '--fixtures' 'renderweave-template\src\test\resources\cn\hbads\renderweave\template\asset-ref-extraction\fixtures.json' `
        '--primary-report' $assetRefPrimaryReport `
        '--report' $assetRefIndependentReport
    if ($LASTEXITCODE -ne 0) {
        throw "Template asset-ref extraction independent replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $assetRefIndependentReport -PathType Leaf)) {
        throw 'Template asset-ref extraction independent replay did not write its report.'
    }
    Write-Host "Template asset-ref extraction evidence: $assetRefIndependentReport"

    $primary = Get-Content -Raw -Encoding UTF8 -LiteralPath $primaryReport | ConvertFrom-Json
    $independent = Get-Content -Raw -Encoding UTF8 -LiteralPath $independentReport | ConvertFrom-Json
    if ($primary.reportVersion -ne 'renderweave-template-kernel-primary/1' `
            -or $primary.engine -ne 'java-primary' `
            -or $primary.cases -ne 211 `
            -or $primary.passed -ne 211 `
            -or $primary.failed -ne 0 `
            -or $primary.profileAvailability -ne 'NOT_REGISTERED') {
        throw 'Template kernel Java primary report boundary drifted.'
    }
    if ($independent.reportVersion -ne 'renderweave-template-kernel-independent/1' `
            -or $independent.engine -ne 'python-independent' `
            -or $independent.assurance -ne 'A2' `
            -or $independent.cases -ne 211 `
            -or $independent.passed -ne 211 `
            -or $independent.failed -ne 0 `
            -or $independent.profileAvailability -ne 'NOT_REGISTERED' `
            -or $independent.vectorSha256 -ne $primary.vectorSha256) {
        throw 'Template kernel independent report boundary drifted.'
    }
    Write-Host (
        'Template kernel: Java={0}/{1} Python={2}/{3} vector=sha256:{4} Profile=NOT_REGISTERED' -f
        $primary.passed, $primary.cases, $independent.passed, $independent.cases,
        $primary.vectorSha256
    )
    Write-Host "Template kernel evidence: $independentReport"
}
finally {
    Pop-Location
}
