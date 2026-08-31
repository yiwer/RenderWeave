param(
    [ValidateSet("Rehearse", "VerifyMissingInput", "VerifyHostFallback")]
    [string]$Mode = "Rehearse",
    [string]$Bundle = "var/renderer-hermetic-build-v2/bundle-v2",
    [string]$WorkVolume
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$image = "sha256:dff7bf7639ce459600e6e042228480eb9b6c627ce590e282c9b1d7c03fcad30b"
$script = "/repo/tools/renderer-exact-build-rehearsal.sh"

$observedImage = (& docker image inspect --format "{{.Id}}" $image).Trim()
if ($LASTEXITCODE -ne 0 -or $observedImage -ne $image) {
    throw "Exact T213 OCI image is unavailable or has a different identity: $observedImage"
}

$dockerArguments = @(
    "run", "--rm", "--network", "none",
    "--cpus", "4", "--memory", "8g", "--pids-limit", "2048",
    "--mount", "type=bind,src=$repoRoot,dst=/repo,readonly"
)

if ($Mode -eq "VerifyMissingInput") {
    $dockerArguments += @("--tmpfs", "/work:rw", $image, "/bin/sh", $script,
        "verify-missing-input-rejected")
} else {
    if ([string]::IsNullOrWhiteSpace($WorkVolume) -or
        $WorkVolume -notmatch "^[A-Za-z0-9][A-Za-z0-9_.-]*$") {
        throw "WorkVolume must be an explicit Docker volume name."
    }
    $bundlePath = (Resolve-Path (Join-Path $repoRoot $Bundle)).Path
    & docker volume create $WorkVolume | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create Docker volume: $WorkVolume"
    }
    $dockerArguments += @(
        "--mount", "type=bind,src=$bundlePath,dst=/bundle,readonly",
        "--mount", "type=volume,src=$WorkVolume,dst=/work"
    )
    $innerMode = if ($Mode -eq "Rehearse") {
        "rehearse"
    } else {
        "verify-host-fallback-rejected"
    }
    $dockerArguments += @($image, "/bin/sh", $script, $innerMode)
}

& docker @dockerArguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Output "Renderer exact build command completed: $Mode"
