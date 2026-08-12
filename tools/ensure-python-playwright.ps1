[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$playwrightVersion = '1.62.0'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolchainsDir = Join-Path $repoRoot '.sdlc\toolchains'
$pythonDir = Join-Path $toolchainsDir "python-playwright-$playwrightVersion"
$pythonExe = Join-Path $pythonDir 'Scripts\python.exe'

if (-not (Test-Path -LiteralPath $pythonExe)) {
    $uvCommand = Get-Command uv.exe -ErrorAction SilentlyContinue
    if (-not $uvCommand) {
        throw 'uv.exe is required to provision the pinned Python Playwright toolchain.'
    }

    $null = New-Item -ItemType Directory -Path $toolchainsDir -Force
    & $uvCommand.Source venv --python 3.12 $pythonDir
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create the Python Playwright environment (exit $LASTEXITCODE)."
    }
    & $uvCommand.Source pip install --python $pythonExe "playwright==$playwrightVersion"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to install Playwright $playwrightVersion (exit $LASTEXITCODE)."
    }
    & $pythonExe -m playwright install chromium
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to install the Playwright Chromium runtime (exit $LASTEXITCODE)."
    }
}

$actualVersion = (& $pythonExe -c "import importlib.metadata as metadata; print(metadata.version('playwright'))").Trim()
if ($LASTEXITCODE -ne 0 -or $actualVersion -ne $playwrightVersion) {
    throw "Expected Python Playwright $playwrightVersion, got $actualVersion at $pythonExe."
}

Write-Output $pythonExe
