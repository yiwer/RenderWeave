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
    throw 'Rendering Pipeline evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Rendering Pipeline evidence directory must already exist.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Rendering Pipeline evidence directory escapes .sdlc/evidence.'
}

$authorityRoot = Join-Path $repoRoot '.scratch\renderweave-template-v1\rendering-pipeline'
$fixtureRoot = Join-Path $authorityRoot 'fixtures'
$target = Join-Path $authorityRoot 'execution-class-target-v1.json'
$javaExecutor = Join-Path $authorityRoot `
    'java-evaluator-and-sealer-executor-manifest-v1.json'
$rustExecutor = Join-Path $authorityRoot `
    'rust-render-document-parser-and-engine-executor-manifest-v1.json'
$templateReport = Join-Path $resolvedEvidenceDir `
    'rendering-pipeline-template-capacity.json'
$javaReport = Join-Path $resolvedEvidenceDir `
    'rendering-pipeline-java-executor.json'
$commandArtifact = Join-Path $resolvedEvidenceDir 'rendering-pipeline-command.json'
$rustReport = Join-Path $resolvedEvidenceDir `
    'rendering-pipeline-rust-executor.json'
$imageArtifact = Join-Path $resolvedEvidenceDir 'rendering-pipeline-output.png'
$classReport = Join-Path $resolvedEvidenceDir `
    'rendering-pipeline-execution-class-independent.json'
foreach ($path in @($target, $javaExecutor, $rustExecutor)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Rendering Pipeline class authority is missing: $path"
    }
}
foreach ($path in @(
        $templateReport, $javaReport, $commandArtifact,
        $rustReport, $imageArtifact, $classReport)) {
    if (Test-Path -LiteralPath $path) {
        throw "Rendering Pipeline evidence already exists: $path"
    }
}

$liveVariables = @(
    'DASHSCOPE_TOKEN_API_KEY',
    'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY',
    'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY',
    'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION',
    'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
)
$liveVariables | ForEach-Object {
    [Environment]::SetEnvironmentVariable($_, $null, 'Process')
}
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

Push-Location $repoRoot
try {
    $mavenArguments = @(
        '-B',
        '-ntp',
        '-pl',
        'renderweave-rendering',
        '-am',
        '-Dtest=TemplateClosureCapacityConformanceTest,RenderingPipelineExecutionClassTest',
        '-Dsurefire.failIfNoSpecifiedTests=false',
        "-Drenderweave.renderingPipeline.fixtureRoot=$fixtureRoot",
        "-Drenderweave.renderingPipeline.target=$target",
        "-Drenderweave.renderingPipeline.templateReport=$templateReport",
        "-Drenderweave.renderingPipeline.javaReport=$javaReport",
        "-Drenderweave.renderingPipeline.command=$commandArtifact",
        'test'
    )
    & mvn.exe @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Rendering Pipeline Java executor failed with exit code $LASTEXITCODE."
    }
    foreach ($path in @($templateReport, $javaReport, $commandArtifact)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Rendering Pipeline Java executor did not produce: $path"
        }
    }

    $env:RENDERWEAVE_RENDERING_PIPELINE_COMMAND = $commandArtifact
    $env:RENDERWEAVE_RENDERING_PIPELINE_RUST_REPORT = $rustReport
    $env:RENDERWEAVE_RENDERING_PIPELINE_IMAGE = $imageArtifact
    & cargo.exe test `
        '--manifest-path' 'renderer/Cargo.toml' `
        '-p' 'renderweave-renderer-daemon' `
        '--test' 'rendering_pipeline_execution_class' `
        '--' '--exact' `
        'replays_java_seal_through_public_parser_document_engine_and_result_chain'
    if ($LASTEXITCODE -ne 0) {
        throw "Rendering Pipeline Rust executor failed with exit code $LASTEXITCODE."
    }
    foreach ($path in @($rustReport, $imageArtifact)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Rendering Pipeline Rust executor did not produce: $path"
        }
    }

    $pythonVersion = (& py.exe -3.13 --version 2>&1).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $pythonVersion.StartsWith('Python 3.13.')) {
        throw "Python 3.13 is required for independent closure replay; got $pythonVersion."
    }
    & py.exe -3.13 'tools\verify-rendering-pipeline-execution-class.py' `
        '--repo' $repoRoot `
        '--target' $target `
        '--java-executor' $javaExecutor `
        '--rust-executor' $rustExecutor `
        '--template-report' $templateReport `
        '--java-report' $javaReport `
        '--command' $commandArtifact `
        '--rust-report' $rustReport `
        '--image' $imageArtifact `
        '--report' $classReport
    if ($LASTEXITCODE -ne 0) {
        throw "Rendering Pipeline independent closure replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $classReport -PathType Leaf)) {
        throw 'Rendering Pipeline independent closure replay did not write its report.'
    }

    $result = Get-Content -Raw -Encoding UTF8 -LiteralPath $classReport |
        ConvertFrom-Json
    if ($result.reportVersion -ne `
            'renderweave-rendering-pipeline-execution-class-independent/1' `
            -or $result.assurance -ne 'A2_PREISSUANCE_PRODUCT_REPLAY' `
            -or $result.executorRoleCount -ne 2 `
            -or $result.capacityAxisCount -ne 52 `
            -or $result.capacityCaseCount -ne 156 `
            -or $result.capacityOracleCount -ne 156 `
            -or $result.capacityAssertionCount -ne 1248 `
            -or $result.boundary.formalCaseCount -ne 253 `
            -or $result.boundary.formalOracleCount -ne 253 `
            -or $result.boundary.formalRecordsIssued -ne 0 `
            -or -not $result.boundary.preissuanceReady `
            -or -not $result.boundary.recordAppendMayProceedInSeparateTicket `
            -or $result.boundary.executionClassExecutable `
            -or $result.boundary.rendererProfileRegistered `
            -or $result.boundary.rendererDaemonOrDeploymentInvoked `
            -or $result.boundary.networkAttempts -ne 0 `
            -or $result.boundary.externalProviderAttempts -ne 0) {
        throw 'Rendering Pipeline execution-class report boundary drifted.'
    }
    Write-Host 'RENDERING_PIPELINE class gate: 2/2 roles, 156/156 capacity, root PNG PASS'
}
finally {
    Pop-Location
    foreach ($name in @(
            'RENDERWEAVE_RENDERING_PIPELINE_COMMAND',
            'RENDERWEAVE_RENDERING_PIPELINE_RUST_REPORT',
            'RENDERWEAVE_RENDERING_PIPELINE_IMAGE')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
}
