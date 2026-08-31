param(
    [switch]$RequireBundle,
    [string]$Bundle = "var/renderer-hermetic-build-v1/bundle-v1"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$lock = Join-Path $repoRoot ".scratch/renderweave-template-v1/renderer-spike/hermetic-build-lock-v1.json"
$bundlePath = Join-Path $repoRoot $Bundle

Set-Location $repoRoot
py -3.13 -m py_compile `
    tools/stage-renderer-hermetic-build.py `
    tools/test_stage_renderer_hermetic_build.py
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

py -3.13 tools/test_stage_renderer_hermetic_build.py
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if (Test-Path -LiteralPath $bundlePath -PathType Container) {
    py -3.13 tools/stage-renderer-hermetic-build.py verify `
        --repo (Join-Path $repoRoot "var/renderer-hermetic-build-v1/repository-unavailable") `
        --lock $lock `
        --bundle $bundlePath
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} elseif ($RequireBundle) {
    Write-Error "Required renderer hermetic build bundle is missing: $bundlePath"
    exit 1
} else {
    Write-Output "Renderer hermetic bundle absent; fixture and lock-behavior checks passed."
}

Write-Output "Renderer hermetic build closure gate passed."
