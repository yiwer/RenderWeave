[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        'VRQ_10_SOLE_DEV_WINNER_SELECTION',
        'VRQ_11_WINNER_HOLDOUT',
        'VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY',
        'VRQ_13_INDEPENDENT_A2_ADMISSION',
        'VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY')]
    [string]$Ticket,
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir,
    [Parameter(Mandatory = $true)]
    [string]$DecisionPath,
    [Parameter(Mandatory = $true)]
    [string[]]$PredecessorPaths
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$ticketNumber = $Ticket.Substring(0, 6).Replace('_', '').ToLowerInvariant()

function Resolve-EvidencePath {
    param([string]$Value, [bool]$MustExist)
    $candidate = [System.IO.Path]::GetFullPath(
        $(if ([System.IO.Path]::IsPathRooted($Value)) { $Value } else { Join-Path $repoRoot $Value }))
    $separator = [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($evidenceRoot + $separator,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Ticket path must be below .sdlc/evidence."
    }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Ticket input evidence is missing."
    }
    return $candidate
}

$resolvedEvidenceDir = [System.IO.Path]::GetFullPath(
    $(if ([System.IO.Path]::IsPathRooted($EvidenceDir)) { $EvidenceDir }
        else { Join-Path $repoRoot $EvidenceDir }))
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase) `
        -or -not (Test-Path -LiteralPath $resolvedEvidenceDir -PathType Container)) {
    throw "$Ticket evidence directory is invalid."
}
$resolvedDecision = Resolve-EvidencePath $DecisionPath $true
if ((Split-Path -Leaf $resolvedDecision) -ne 'vrq07-decision.json') {
    throw "$Ticket requires the canonical VRQ-07 decision filename."
}
if ($Ticket -eq 'VRQ_10_SOLE_DEV_WINNER_SELECTION') {
    $expectedNames = @('vrq08-outcome.json', 'vrq09-outcome.json')
}
elseif ($Ticket -eq 'VRQ_11_WINNER_HOLDOUT') {
    $expectedNames = @('vrq10-outcome.json')
}
elseif ($Ticket -eq 'VRQ_12_IMAGE_ONLY_SCRIPTED_REPLAY') {
    $expectedNames = @('vrq11-outcome.json')
}
elseif ($Ticket -eq 'VRQ_13_INDEPENDENT_A2_ADMISSION') {
    $expectedNames = @('vrq12-outcome.json')
}
elseif ($Ticket -eq 'VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY') {
    $expectedNames = @('vrq13-outcome.json')
}
if ($PredecessorPaths.Count -ne $expectedNames.Count) {
    throw "$Ticket predecessor count is invalid."
}
$resolvedPredecessors = @()
for ($index = 0; $index -lt $expectedNames.Count; $index++) {
    $resolved = Resolve-EvidencePath $PredecessorPaths[$index] $true
    if ((Split-Path -Leaf $resolved) -ne $expectedNames[$index]) {
        throw "$Ticket predecessor filename is invalid."
    }
    $resolvedPredecessors += $resolved
}
$outcomePath = Join-Path $resolvedEvidenceDir "$ticketNumber-outcome.json"
$verifierPath = Join-Path $resolvedEvidenceDir "$ticketNumber-outcome-a2.json"
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
for ($index = 0; $index -lt $resolvedPredecessors.Count; $index++) {
    [Environment]::SetEnvironmentVariable(
        "RENDERWEAVE_OFFLINE_TERMINAL_PREDECESSOR_$($index + 1)",
        $resolvedPredecessors[$index], 'Process')
}

Push-Location $repoRoot
try {
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        '-Dtest=OfflineRepairTerminalGateTest,OfflineRepairTerminalEvidenceGateTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' test
    if (-not $? -or $LASTEXITCODE -ne 0) { throw "$Ticket Java gate failed." }
    $arguments = @(
        'tools/verify_offline_repair_terminal_outcome.py',
        '--ticket', $Ticket,
        '--decision', $resolvedDecision,
        '--outcome', $outcomePath,
        '--repository', $repoRoot,
        '--output', $verifierPath)
    foreach ($predecessor in $resolvedPredecessors) {
        $arguments += @('--predecessor', $predecessor)
    }
    & python.exe @arguments
    if (-not $? -or $LASTEXITCODE -ne 0) { throw "$Ticket independent verifier failed." }
}
finally {
    @(
        'RENDERWEAVE_RUN_OFFLINE_TERMINAL_GATE',
        'RENDERWEAVE_OFFLINE_TERMINAL_TICKET',
        'RENDERWEAVE_OFFLINE_TERMINAL_DECISION',
        'RENDERWEAVE_OFFLINE_TERMINAL_OUTCOME',
        'RENDERWEAVE_OFFLINE_TERMINAL_PREDECESSOR_1',
        'RENDERWEAVE_OFFLINE_TERMINAL_PREDECESSOR_2'
    ) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
    Pop-Location
}

$result = if ($Ticket -eq 'VRQ_14_FRESH_LIVE_REQUEST_ELIGIBILITY') {
    'LIVE_J1_REQUEST_NOT_ELIGIBLE'
} else {
    'BLOCKED_BY_PREDECESSOR'
}
Write-Host "$Ticket result: $result; cases=0; ProviderAttempts=0"
Write-Host "$Ticket evidence: $resolvedEvidenceDir"
