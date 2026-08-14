[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('VRQ_08_PP_STRUCTUREV3_DEV_SHADOW', 'VRQ_09_TESSERACT_DEV_BASELINE')]
    [string]$Ticket,
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$ticketNumber = if ($Ticket.StartsWith('VRQ_08')) { 'vrq08' } else { 'vrq09' }

function Resolve-EvidencePath {
    param([string]$Value, [bool]$MustExist)
    $candidate = [System.IO.Path]::GetFullPath(
        $(if ([System.IO.Path]::IsPathRooted($Value)) { $Value } else { Join-Path $repoRoot $Value }))
    $separator = [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($evidenceRoot + $separator,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Ticket path must be below .sdlc/evidence."
    }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate)) {
        throw "$Ticket input evidence is missing."
    }
    return $candidate
}

$resolvedEvidenceDir = Resolve-EvidencePath $EvidenceDir $true
if (-not (Test-Path -LiteralPath $resolvedEvidenceDir -PathType Container)) {
    throw "$Ticket evidence directory must already exist."
}
$resolvedDecision = Resolve-EvidencePath $DecisionPath $true
if ((Split-Path -Leaf $resolvedDecision) -ne 'vrq07-decision.json') {
    throw "$Ticket requires the canonical VRQ-07 decision filename."
}
$outcomePath = Join-Path $resolvedEvidenceDir "$ticketNumber-outcome.json"
$verifierPath = Join-Path $resolvedEvidenceDir "$ticketNumber-outcome-verification.json"
foreach ($path in @($outcomePath, $verifierPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "$Ticket evidence output already exists: $(Split-Path -Leaf $path)"
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
$env:RENDERWEAVE_OFFLINE_TERMINAL_TICKET = $Ticket
$env:RENDERWEAVE_OFFLINE_TERMINAL_DECISION = $resolvedDecision
$env:RENDERWEAVE_OFFLINE_TERMINAL_OUTCOME = $outcomePath

Push-Location $repoRoot
try {
    & python.exe tools/test_offline_quality_resources.py
    if (-not $? -or $LASTEXITCODE -ne 0) { throw "$Ticket resource contract tests failed." }
    & python.exe tools/test_verify_offline_repair_terminal_outcome.py
    if (-not $? -or $LASTEXITCODE -ne 0) { throw "$Ticket verifier regression tests failed." }
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        '-Dtest=OfflineRepairTerminalGateTest,OfflineRepairTerminalEvidenceGateTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if (-not $? -or $LASTEXITCODE -ne 0) { throw "$Ticket Java gate failed." }
    & python.exe tools/verify_offline_repair_terminal_outcome.py `
        --ticket $Ticket --decision $resolvedDecision --outcome $outcomePath `
        --repository $repoRoot --output $verifierPath
    if (-not $? -or $LASTEXITCODE -ne 0) { throw "$Ticket independent verifier failed." }
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

Write-Host "$Ticket result: STOPPED_FOR_R5_SUCCESSOR_SPEC; cases=0; ProviderAttempts=0"
Write-Host "$Ticket evidence: $resolvedEvidenceDir"
