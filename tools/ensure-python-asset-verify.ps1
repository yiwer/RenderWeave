[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$pillowVersion = '11.3.0'
$fonttoolsVersion = '4.60.1'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolchainsDir = Join-Path $repoRoot '.sdlc\toolchains'
$pythonDir = Join-Path $toolchainsDir "python-asset-verify-$pillowVersion"
$pythonExe = Join-Path $pythonDir 'Scripts\python.exe'

if (-not (Test-Path -LiteralPath $pythonExe)) {
    $uvCommand = Get-Command uv.exe -ErrorAction SilentlyContinue
    if (-not $uvCommand) {
        throw 'uv.exe is required to provision the pinned Python Asset verify toolchain.'
    }

    $null = New-Item -ItemType Directory -Path $toolchainsDir -Force
    & $uvCommand.Source venv --python 3.12 $pythonDir
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create the Python Asset verify environment (exit $LASTEXITCODE)."
    }
    & $uvCommand.Source pip install --python $pythonExe `
        --default-index 'https://pypi.tuna.tsinghua.edu.cn/simple' `
        "pillow==$pillowVersion" "fonttools==$fonttoolsVersion"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to install Pillow/fontTools (exit $LASTEXITCODE)."
    }
}

$actualPillow = (& $pythonExe -c "import importlib.metadata as m; print(m.version('pillow'))").Trim()
$actualFonttools = (& $pythonExe -c "import importlib.metadata as m; print(m.version('fonttools'))").Trim()
if ($LASTEXITCODE -ne 0 -or $actualPillow -ne $pillowVersion -or $actualFonttools -ne $fonttoolsVersion) {
    throw "Expected Pillow $pillowVersion/fontTools $fonttoolsVersion, got $actualPillow/$actualFonttools."
}

Write-Output $pythonExe
