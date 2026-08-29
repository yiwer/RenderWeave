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
    throw 'Design/Input/Expression evidence directory must be below .sdlc/evidence.'
}
if (-not (Test-Path -LiteralPath $candidateEvidenceDir -PathType Container)) {
    throw 'Design/Input/Expression evidence directory must already exist.'
}
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $candidateEvidenceDir).Path
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Design/Input/Expression evidence directory escapes .sdlc/evidence.'
}

$target = Join-Path $repoRoot `
    '.scratch\renderweave-template-v1\design-input-expression\capacity-component-target-v9.json'
$primaryReport = Join-Path $resolvedEvidenceDir `
    'design-input-expression-capacity-primary.json'
$independentReport = Join-Path $resolvedEvidenceDir `
    'design-input-expression-capacity-independent.json'
$independentExecutor = Join-Path $repoRoot `
    'tools\verify-design-input-expression-capacity.ts'
foreach ($path in @($target, $independentExecutor)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Design/Input/Expression component authority is missing: $path"
    }
}
foreach ($path in @($primaryReport, $independentReport)) {
    if (Test-Path -LiteralPath $path) {
        throw "Design/Input/Expression evidence already exists: $path"
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
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        '-Dtest=cn.hbads.renderweave.template.internal.DesignInputExpressionCapacityConformanceTest,cn.hbads.renderweave.template.internal.DesignDslCapacityReservationTest,cn.hbads.renderweave.template.internal.DesignDslSemanticCapacityReservationTest,cn.hbads.renderweave.template.internal.ExpressionDefinitionCapacityReservationTest,cn.hbads.renderweave.template.internal.ExpressionDecimalCapacityReservationTest,cn.hbads.renderweave.template.internal.GeometryCapacityReservationTest,cn.hbads.renderweave.template.internal.TemplateProblemCapacityReservationTest,cn.hbads.renderweave.rendering.internal.ExpressionEngineTest,cn.hbads.renderweave.rendering.internal.ExpressionDecimalCapacityEvaluationTest,cn.hbads.renderweave.rendering.internal.MaterializerTest,cn.hbads.renderweave.rendering.internal.RenderInputCapacityReservationTest,cn.hbads.renderweave.app.rendering.RenderingControllerCapacityReservationTest,cn.hbads.renderweave.app.rendering.RenderingApplicationConfigurationTest,cn.hbads.renderweave.app.template.TemplateApplicationConfigurationTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' `
        "-Drenderweave.designInputExpression.target=$target" `
        "-Drenderweave.designInputExpression.primaryReport=$primaryReport" `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "Design/Input/Expression Java component replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $primaryReport -PathType Leaf)) {
        throw 'Design/Input/Expression Java replay did not write its report.'
    }

    $nodeDir = (& powershell.exe -NoProfile -ExecutionPolicy Bypass `
            -File (Join-Path $PSScriptRoot 'ensure-node24.ps1') |
        Select-Object -Last 1)
    if ($LASTEXITCODE -ne 0 -or -not $nodeDir) {
        throw 'Unable to provision the pinned Node 24 toolchain.'
    }
    & (Join-Path $nodeDir 'node.exe') $independentExecutor `
        '--repo' $repoRoot `
        '--target' $target `
        '--primary-report' $primaryReport `
        '--report' $independentReport
    if ($LASTEXITCODE -ne 0) {
        throw "Design/Input/Expression TypeScript replay failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path -LiteralPath $independentReport -PathType Leaf)) {
        throw 'Design/Input/Expression TypeScript replay did not write its report.'
    }

    $result = Get-Content -Raw -Encoding UTF8 -LiteralPath $independentReport |
        ConvertFrom-Json
    if ($result.reportVersion -ne `
            'renderweave-design-input-expression-capacity-independent/1' `
            -or $result.assurance -ne `
            'A2_COMPONENT_SCALAR_REPLAY_PARTIAL_PRODUCT_WIRING' `
            -or $result.axisCount -ne 65 `
            -or $result.caseCount -ne 195 `
            -or $result.passed -ne 195 `
            -or $result.failed -ne 0 `
            -or $result.boundary.wiredProductAxisCount -ne 57 `
            -or $result.boundary.remainingProductAxisCount -ne 8 `
            -or $result.boundary.preissuanceReady `
            -or $result.boundary.recordIssuanceAllowed `
            -or $result.boundary.executionClassExecutable) {
        throw 'Design/Input/Expression independent report boundary drifted.'
    }
    Write-Host 'DESIGN_INPUT_EXPRESSION component gate: 195/195 scalar PASS, 57/65 wired'
}
finally {
    Pop-Location
}
