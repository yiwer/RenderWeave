[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path

function Resolve-EvidencePath {
    param([string]$Value, [bool]$MustExist)
    $candidate = [System.IO.Path]::GetFullPath(
        $(if ([System.IO.Path]::IsPathRooted($Value)) { $Value } else { Join-Path $repoRoot $Value }))
    $separator = [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($evidenceRoot + $separator,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'VRQ-08 path must be below .sdlc/evidence.'
    }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate)) {
        throw 'VRQ-08 input evidence is missing.'
    }
    return $candidate
}

$resolvedEvidenceDir = Resolve-EvidencePath $EvidenceDir $true
if (-not (Test-Path -LiteralPath $resolvedEvidenceDir -PathType Container)) {
    throw 'VRQ-08 evidence directory must already exist.'
}
$resolvedDecision = Resolve-EvidencePath $DecisionPath $true
if ((Split-Path -Leaf $resolvedDecision) -ne 'vrq07-decision.json') {
    throw 'VRQ-08 requires the canonical VRQ-07 decision filename.'
}
$outcomePath = Join-Path $resolvedEvidenceDir 'vrq08-outcome.json'
$verifierPath = Join-Path $resolvedEvidenceDir 'vrq08-outcome-a2.json'
foreach ($path in @($outcomePath, $verifierPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "VRQ-08 evidence output already exists: $(Split-Path -Leaf $path)"
    }
}

@(
    'DASHSCOPE_API_KEY', 'DASHSCOPE_API_KEY_FILE',
    'DASHSCOPE_TOKEN_API_KEY', 'DASHSCOPE_TOKEN_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY', 'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_VISUAL_EVALUATION', 'RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'
$env:RENDERWEAVE_RUN_OFFLINE_TERMINAL_GATE = 'true'
$env:RENDERWEAVE_OFFLINE_TERMINAL_TICKET = 'VRQ_08_PP_STRUCTUREV3_DEV_SHADOW'
$env:RENDERWEAVE_OFFLINE_TERMINAL_DECISION = $resolvedDecision
$env:RENDERWEAVE_OFFLINE_TERMINAL_OUTCOME = $outcomePath

Push-Location $repoRoot
try {
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        '-Dtest=OfflineRepairTerminalGateTest,OfflineRepairTerminalEvidenceGateTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if (-not $? -or $LASTEXITCODE -ne 0) { throw 'VRQ-08 Java gate failed.' }
    & python.exe tools/verify_offline_repair_terminal_outcome.py `
        --ticket VRQ_08_PP_STRUCTUREV3_DEV_SHADOW `
        --decision $resolvedDecision --outcome $outcomePath `
        --repository $repoRoot --output $verifierPath
    if (-not $? -or $LASTEXITCODE -ne 0) { throw 'VRQ-08 independent verifier failed.' }
}
finally {
    @(
        'RENDERWEAVE_RUN_OFFLINE_TERMINAL_GATE',
        'RENDERWEAVE_OFFLINE_TERMINAL_TICKET',
        'RENDERWEAVE_OFFLINE_TERMINAL_DECISION',
        'RENDERWEAVE_OFFLINE_TERMINAL_OUTCOME'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

Write-Host 'VRQ-08 result: STOPPED_FOR_R5_SUCCESSOR_SPEC; cases=0; ProviderAttempts=0'
Write-Host "VRQ-08 evidence: $resolvedEvidenceDir"
