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
$domainPrimaryReport = Join-Path $resolvedEvidenceDir 'domain-services-capacity-primary.json'
$domainIndependentReport = Join-Path $resolvedEvidenceDir 'domain-services-capacity-independent.json'
$domainFixtureSnapshot = Join-Path $resolvedEvidenceDir 'domain-services-capacity-fixtures'
$domainTarget = Join-Path $repoRoot `
    '.scratch\renderweave-template-v1\domain-services\product-execution-target-v1.json'
$domainCoverage = Join-Path $repoRoot `
    '.scratch\renderweave-template-v1\conformance-capacity-coverage-v1.json'
foreach ($report in @(
        $primaryReport,
        $independentReport,
        $domainPrimaryReport,
        $domainIndependentReport
    )) {
    if (Test-Path -LiteralPath $report) {
        throw "Asset kernel evidence already exists: $report"
    }
}
if (Test-Path -LiteralPath $domainFixtureSnapshot) {
    throw "Domain Services fixture snapshot already exists: $domainFixtureSnapshot"
}
foreach ($authority in @($domainTarget, $domainCoverage)) {
    if (-not (Test-Path -LiteralPath $authority -PathType Leaf)) {
        throw "Domain Services capacity authority is missing: $authority"
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
    $pythonExe = (& powershell.exe -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $PSScriptRoot 'ensure-python-asset-verify.ps1') | Select-Object -Last 1)
    if ($LASTEXITCODE -ne 0 -or -not $pythonExe) {
        throw 'Unable to provision the pinned Asset verify toolchain.'
    }

    & $pythonExe 'tools\materialize-domain-services-capacity-fixtures.py' `
        '--repo' $repoRoot `
        '--target' $domainTarget `
        '--output' $domainFixtureSnapshot
    if ($LASTEXITCODE -ne 0) {
        throw "Domain Services fixture materialization failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $domainFixtureSnapshot -PathType Container) -or
            (Get-ChildItem -LiteralPath $domainFixtureSnapshot -File).Count -ne 12) {
        throw 'Domain Services fixture materialization did not produce exactly 12 files.'
    }

    & mvn.cmd -B -ntp -pl renderweave-asset -am `
        "-Drenderweave.asset.primaryReport=$primaryReport" `
        "-Drenderweave.domainServices.fixtureRoot=$domainFixtureSnapshot" `
        "-Drenderweave.domainServices.target=$domainTarget" `
        "-Drenderweave.domainServices.primaryReport=$domainPrimaryReport" test
    if ($LASTEXITCODE -ne 0) {
        throw "Asset kernel Java primary failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $primaryReport -PathType Leaf)) {
        throw 'Asset kernel Java primary did not write its report.'
    }
    if (-not (Test-Path -LiteralPath $domainPrimaryReport -PathType Leaf)) {
        throw 'Domain Services Java primary did not write its report.'
    }

    & $pythonExe 'tools\verify-asset-kernel-vectors.py' `
        '--vectors' 'renderweave-asset\src\test\resources\cn\hbads\renderweave\asset\acceptance-kernel-v1\vectors.json' `
        '--primary-report' $primaryReport `
        '--report' $independentReport `
        '--canonical-icc' 'renderweave-asset\src\main\resources\cn\hbads\renderweave\asset\acceptance\sRGB-IEC61966-2.1.icc'
    if ($LASTEXITCODE -ne 0) {
        throw "Asset kernel independent replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $independentReport -PathType Leaf)) {
        throw 'Asset kernel independent replay did not write its report.'
    }

    & $pythonExe 'tools\verify-domain-services-capacity.py' `
        '--repo' $repoRoot `
        '--coverage' $domainCoverage `
        '--fixtures' $domainFixtureSnapshot `
        '--primary-report' $domainPrimaryReport `
        '--target' $domainTarget `
        '--report' $domainIndependentReport
    if ($LASTEXITCODE -ne 0) {
        throw "Domain Services independent replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $domainIndependentReport -PathType Leaf)) {
        throw 'Domain Services independent replay did not write its report.'
    }

    $primary = Get-Content -Raw -Encoding UTF8 -LiteralPath $primaryReport | ConvertFrom-Json
    $independent = Get-Content -Raw -Encoding UTF8 -LiteralPath $independentReport | ConvertFrom-Json
    $domainPrimary = Get-Content -Raw -Encoding UTF8 `
        -LiteralPath $domainPrimaryReport | ConvertFrom-Json
    $domainIndependent = Get-Content -Raw -Encoding UTF8 `
        -LiteralPath $domainIndependentReport | ConvertFrom-Json
    if ($primary.reportVersion -ne 'renderweave-asset-kernel-primary/1' `
            -or $primary.engine -ne 'java-primary' `
            -or $primary.cases -ne 41 `
            -or $primary.passed -ne 41 `
            -or $primary.failed -ne 0 `
            -or $primary.acceptanceProfileAvailability -ne 'NOT_REGISTERED') {
        throw 'Asset kernel Java primary report boundary drifted.'
    }
    if ($independent.reportVersion -ne 'renderweave-asset-kernel-independent/1' `
            -or $independent.engine -ne 'python-independent' `
            -or $independent.assurance -ne 'A2' `
            -or $independent.cases -ne 41 `
            -or $independent.passed -ne 41 `
            -or $independent.failed -ne 0 `
            -or $independent.acceptanceProfileAvailability -ne 'NOT_REGISTERED' `
            -or $independent.vectorSha256 -ne $primary.vectorSha256) {
        throw 'Asset kernel independent report boundary drifted.'
    }
    if ($domainPrimary.reportVersion -ne 'renderweave-domain-services-capacity-primary/1' `
            -or $domainPrimary.engine -ne 'java-domain-authority' `
            -or $domainPrimary.assurance -ne 'A1_EXACT_PRODUCT_EXECUTION' `
            -or $domainPrimary.caseCount -ne 12 `
            -or $domainPrimary.passed -ne 12 `
            -or $domainPrimary.failed -ne 0 `
            -or $domainPrimary.boundary.mediaPayloadAllocated `
            -or $domainPrimary.boundary.databaseUsed `
            -or $domainPrimary.boundary.formalRecordsIssued -ne 0) {
        throw 'Domain Services Java primary report boundary drifted.'
    }
    if ($domainIndependent.reportVersion -ne `
                'renderweave-domain-services-capacity-independent/1' `
            -or $domainIndependent.engine -ne 'python-independent' `
            -or $domainIndependent.assurance -ne 'A2_EXACT_OBSERVATION_REPLAY' `
            -or $domainIndependent.caseCount -ne 12 `
            -or $domainIndependent.passed -ne 12 `
            -or $domainIndependent.failed -ne 0 `
            -or $domainIndependent.implementationRevision -ne `
                $domainPrimary.implementationRevision `
            -or $domainIndependent.targetManifest.sha256 -ne `
                $domainPrimary.targetManifest.sha256 `
            -or $domainIndependent.boundary.mediaPayloadAllocated `
            -or $domainIndependent.boundary.databaseUsed `
            -or $domainIndependent.boundary.formalRecordsIssued -ne 0 `
            -or $domainIndependent.boundary.recordIssuanceAllowed `
            -or $domainIndependent.boundary.networkAttempts -ne 0 `
            -or $domainIndependent.boundary.externalProviderAttempts -ne 0) {
        throw 'Domain Services independent report boundary drifted.'
    }
    Write-Host (
        'Asset kernel: Java={0}/{1} Python={2}/{3} vector=sha256:{4} Profile=NOT_REGISTERED' -f
        $primary.passed, $primary.cases, $independent.passed, $independent.cases,
        $primary.vectorSha256
    )
    Write-Host (
        'Domain Services capacity: Java={0}/{1} Python={2}/{3} target={4} records=0' -f
        $domainPrimary.passed, $domainPrimary.caseCount,
        $domainIndependent.passed, $domainIndependent.caseCount,
        $domainPrimary.implementationRevision
    )
    Write-Host "Asset kernel evidence: $independentReport"
    Write-Host "Domain Services capacity evidence: $domainIndependentReport"
}
finally {
    Pop-Location
}
