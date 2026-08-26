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
    throw 'Domain Services evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Domain Services evidence directory must already exist.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Domain Services evidence directory escapes .sdlc/evidence.'
}

$target = Join-Path $repoRoot `
    '.scratch\renderweave-template-v1\domain-services\execution-class-target-v1.json'
$javaExecutor = Join-Path $repoRoot `
    '.scratch\renderweave-template-v1\domain-services\java-domain-authority-executor-manifest-v1.json'
$transactionalExecutor = Join-Path $repoRoot `
    '.scratch\renderweave-template-v1\domain-services\transactional-integration-replayer-manifest-v1.json'
$transactionalReport = Join-Path $resolvedEvidenceDir `
    'domain-services-transactional-replay.json'
$classReport = Join-Path $resolvedEvidenceDir `
    'domain-services-execution-class-independent.json'
$capacityPrimary = Join-Path $resolvedEvidenceDir `
    'domain-services-capacity-primary.json'
$capacityIndependent = Join-Path $resolvedEvidenceDir `
    'domain-services-capacity-independent.json'
foreach ($path in @($target, $javaExecutor, $transactionalExecutor)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Domain Services authority is missing: $path"
    }
}
foreach ($path in @($transactionalReport, $classReport)) {
    if (Test-Path -LiteralPath $path) {
        throw "Domain Services evidence already exists: $path"
    }
}

# Exact product replay is offline except for the local Docker API and an already-present
# immutable PostgreSQL image. It must never inspect or inherit Provider authorization.
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

$expectedPostgresImage = `
    'sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50'
$observedPostgresImage = (& docker image inspect postgres:16-alpine --format '{{.Id}}')
if ($LASTEXITCODE -ne 0 -or $observedPostgresImage -ne $expectedPostgresImage) {
    throw 'Frozen postgres:16-alpine image is not already present at the exact required digest.'
}

Push-Location $repoRoot
try {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $PSScriptRoot 'run-asset-kernel-gate.ps1') `
        -EvidenceDir $resolvedEvidenceDir
    if ($LASTEXITCODE -ne 0) {
        throw "Domain Services Java authority gate failed with exit code $LASTEXITCODE."
    }
    foreach ($path in @($capacityPrimary, $capacityIndependent)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Domain Services capacity replay did not produce: $path"
        }
    }

    & mvn.cmd -B -ntp -pl renderweave-app -am `
        '-Dtest=cn.hbads.renderweave.app.asset.DomainServicesTransactionalConformanceTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' `
        "-Drenderweave.domainServices.executionClassTarget=$target" `
        "-Drenderweave.domainServices.transactionalReport=$transactionalReport" `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "Domain Services PostgreSQL replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $transactionalReport -PathType Leaf)) {
        throw 'Domain Services PostgreSQL replay did not write its report.'
    }

    $pythonExe = (& powershell.exe -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $PSScriptRoot 'ensure-python-asset-verify.ps1') |
        Select-Object -Last 1)
    if ($LASTEXITCODE -ne 0 -or -not $pythonExe) {
        throw 'Unable to provision the pinned Domain Services verify toolchain.'
    }
    & $pythonExe 'tools\verify-domain-services-execution-class.py' `
        '--repo' $repoRoot `
        '--target' $target `
        '--java-executor' $javaExecutor `
        '--transactional-executor' $transactionalExecutor `
        '--capacity-primary' $capacityPrimary `
        '--capacity-independent' $capacityIndependent `
        '--transactional-report' $transactionalReport `
        '--report' $classReport
    if ($LASTEXITCODE -ne 0) {
        throw "Domain Services independent closure replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $classReport -PathType Leaf)) {
        throw 'Domain Services independent closure replay did not write its report.'
    }

    $result = Get-Content -Raw -Encoding UTF8 -LiteralPath $classReport |
        ConvertFrom-Json
    if ($result.reportVersion -ne `
            'renderweave-domain-services-execution-class-independent/1' `
            -or $result.assurance -ne 'A2_PREISSUANCE_PRODUCT_REPLAY' `
            -or $result.executorRoleCount -ne 2 `
            -or $result.capacityCaseCount -ne 12 `
            -or $result.transactionScenarioCount -ne 3 `
            -or -not $result.boundary.preissuanceReady `
            -or $result.boundary.formalRecordsIssued -ne 0 `
            -or $result.boundary.executionClassExecutable) {
        throw 'Domain Services execution-class report boundary drifted.'
    }
    Write-Host 'DOMAIN_SERVICES gate: 2/2 roles, 12/12 capacity, 3/3 transaction PASS'
}
finally {
    Pop-Location
}
