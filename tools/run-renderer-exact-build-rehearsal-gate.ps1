param(
    [switch]$WithDocker,
    [string]$HostFallbackVolume
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

Set-Location $repoRoot
py -3.13 -m py_compile `
    renderer/probes/t213/audit_rehearsal.py `
    renderer/probes/t213/test_audit_rehearsal.py
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

py -3.13 -m unittest discover `
    -s renderer/probes/t213 `
    -p test_*.py `
    -v
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if ($WithDocker) {
    powershell -ExecutionPolicy Bypass -File `
        tools/run-renderer-exact-build-rehearsal.ps1 `
        -Mode VerifyMissingInput
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    if (-not [string]::IsNullOrWhiteSpace($HostFallbackVolume)) {
        powershell -ExecutionPolicy Bypass -File `
            tools/run-renderer-exact-build-rehearsal.ps1 `
            -Mode VerifyHostFallback `
            -WorkVolume $HostFallbackVolume
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
}

Write-Output "Renderer exact build rehearsal gate passed."
