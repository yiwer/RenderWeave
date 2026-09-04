[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidenceRoot = (Resolve-Path (Join-Path $repoRoot '.sdlc\evidence')).Path
$resolvedEvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
$separator = [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedEvidenceDir.StartsWith(
        $evidenceRoot + $separator, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'IMAGE_ONLY P2 OCR sidecar evidence must be below .sdlc/evidence.'
}

@(
    'DASHSCOPE_TOKEN_API_KEY', 'DASHSCOPE_TOKEN_API_KEY_FILE',
    'DASHSCOPE_API_KEY', 'DASHSCOPE_API_KEY_FILE',
    'RENDERWEAVE_RUN_LIVE_CANARY', 'RENDERWEAVE_RUN_LIVE_CERTIFICATION',
    'RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION',
    'RENDERWEAVE_RUN_PROFILE_SUCCESSOR_DIAGNOSTIC',
    'RENDERWEAVE_RUN_V52_PROFILE_SUCCESSOR_DIAGNOSTIC'
) | ForEach-Object { [Environment]::SetEnvironmentVariable($_, $null, 'Process') }
$env:RENDERWEAVE_LIVE_AI_ENABLED = 'false'
$env:RENDERWEAVE_LIVE_UPLOAD_ENABLED = 'false'

$summaryPath = Join-Path $resolvedEvidenceDir 'image-only-p2-ocr-sidecar-summary.json'
if (Test-Path -LiteralPath $summaryPath) {
    throw 'IMAGE_ONLY P2 OCR sidecar summary already exists.'
}
$noProxy = @(
    '--env', 'HTTP_PROXY=', '--env', 'HTTPS_PROXY=', '--env', 'http_proxy=',
    '--env', 'https_proxy=', '--env', 'ALL_PROXY=', '--env', 'NO_PROXY=*'
)
$imageTag = 'renderweave-ocr-sidecar:p2-06-gate'
$containerPrefix = 'p2-06-ocr-gate'
$containerIds = New-Object System.Collections.Generic.List[string]

function Remove-GateContainers {
    foreach ($id in $containerIds) {
        docker rm -f $id 2>$null | Out-Null
    }
}

Push-Location $repoRoot
try {
    & python.exe tools\test_verify_image_only_p2_ocr_sidecar.py
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 OCR sidecar verifier tests failed with exit code $LASTEXITCODE."
    }

    # Offline build: --network=none fails the build closed on any download attempt.
    docker build --network=none -t $imageTag docker/ocr-sidecar
    if ($LASTEXITCODE -ne 0) {
        throw 'IMAGE_ONLY P2 OCR sidecar offline build failed.'
    }
    $imageId = (docker image inspect --format '{{.Id}}' $imageTag) | Out-String
    $imageId = $imageId.Trim()

    # Startup probe (blocking layer): capability identity + synthetic OCR probe.
    $startupProbe = Join-Path $resolvedEvidenceDir 'sidecar-startup-probe.json'
    docker run --rm --network=none --memory=2g --cpus=2 --pids-limit=64 $noProxy `
        $imageTag --probe-only | Set-Content -Path $startupProbe -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw 'IMAGE_ONLY P2 OCR sidecar startup probe failed.'
    }

    # UDS probe container at the production confinement values.
    $udsName = "$containerPrefix-uds"
    docker run -d --name $udsName --network=none --read-only --memory=2g --cpus=2 `
        --pids-limit=64 --tmpfs /run/ocr:uid=10001,gid=999,mode=1770,size=8m `
        --tmpfs /tmp:size=64m,mode=1777 $noProxy $imageTag | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'IMAGE_ONLY P2 OCR sidecar UDS container failed to start.'
    }
    $containerIds.Add($udsName)
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 1
        $logs = (docker logs $udsName 2>&1) -join "`n"
        if ($logs -match 'OCR_SIDECAR_READY') { $ready = $true; break }
    }
    if (-not $ready) {
        throw 'IMAGE_ONLY P2 OCR sidecar never became ready.'
    }

    $udsProbe = Join-Path $resolvedEvidenceDir 'sidecar-uds-probe.json'
    docker exec -e PYTHONPATH=/opt/ocr-sidecar $udsName `
        python /opt/ocr-sidecar/probe_client.py --mode all |
        Set-Content -Path $udsProbe -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw 'IMAGE_ONLY P2 OCR sidecar UDS probe failed.'
    }

    # Hardening evidence: non-root, read-only rootfs, dropped capabilities.
    $runtimeUid = (docker exec $udsName id -u).Trim()
    $capEffLine = (docker exec $udsName sh -c 'grep CapEff /proc/1/status') | Out-String
    $effectiveCapabilities = ''
    if ($capEffLine -match 'CapEff:\s*([0-9a-fA-F]+)') {
        $effectiveCapabilities = $Matches[1].ToLowerInvariant()
    }
    docker exec $udsName sh -c 'touch /probe-write 2>/dev/null'
    $rootfsWritable = ($LASTEXITCODE -eq 0)
    $hardening = @{
        imageId = $imageId
        runtimeUid = [int]$runtimeUid
        rootfsWritable = $rootfsWritable
        effectiveCapabilities = $effectiveCapabilities
    } | ConvertTo-Json -Compress
    Set-Content -Path (Join-Path $resolvedEvidenceDir 'sidecar-hardening.json') `
        -Value $hardening -Encoding utf8

    # R0 behavior-equivalence: stdio adapter vs UDS sidecar, byte-identical output.
    # Measurement headroom uses pids-limit 128 because two engine instances load at once;
    # the production confinement values (64) are asserted on the UDS container above.
    # Measurement harness: read-only confinement is asserted on the UDS container above;
    # this instance accepts the dev adapter copy for the byte-identical comparison.
    $eqName = "$containerPrefix-equivalence"
    docker run -d --name $eqName --network=none --memory=2g --cpus=2 `
        --pids-limit=128 --tmpfs /run/ocr:uid=10001,gid=999,mode=1770,size=8m `
        --tmpfs /tmp:size=64m,mode=1777 $noProxy $imageTag | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'IMAGE_ONLY P2 OCR sidecar equivalence container failed to start.'
    }
    $containerIds.Add($eqName)
    $eqReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Start-Sleep -Seconds 1
        $logs = (docker logs $eqName 2>&1) -join "`n"
        if ($logs -match 'OCR_SIDECAR_READY') { $eqReady = $true; break }
    }
    if (-not $eqReady) {
        throw 'IMAGE_ONLY P2 OCR sidecar equivalence instance never became ready.'
    }
    # docker cp does not traverse the tmpfs mount over /tmp; stage under /opt/equivalence.
    docker exec --user root $eqName mkdir -p /opt/equivalence
    docker cp tools/document-vision/rapidocr_adapter.py `
        "${eqName}:/opt/equivalence/rapidocr_adapter.py"
    docker cp docker/ocr-sidecar/equivalence_probe.py `
        "${eqName}:/opt/equivalence/equivalence_probe.py"
    $equivalence = Join-Path $resolvedEvidenceDir 'sidecar-equivalence.json'
    docker exec $eqName python /opt/equivalence/equivalence_probe.py |
        Set-Content -Path $equivalence -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw 'IMAGE_ONLY P2 OCR sidecar equivalence probe failed.'
    }

    $appTests = @(
        'UnixDomainSocketDocumentVisionRunnerTest',
        'LocalProcessDocumentVisionPreprocessorTest',
        'DocumentVisionContractTest',
        'LocalProcessVisualEvidenceAcquisitionTest',
        'InferenceControllerPolicyTest'
    ) -join ','
    & mvn.cmd -B -ntp -pl renderweave-app -am `
        "-Dtest=$appTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) {
        throw "IMAGE_ONLY P2 OCR sidecar tests failed with exit code $LASTEXITCODE."
    }

    & python.exe tools\verify_image_only_p2_ocr_sidecar.py `
        --repository $repoRoot --evidence $resolvedEvidenceDir --output $summaryPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) {
        throw 'IMAGE_ONLY P2 OCR sidecar verifier did not produce a passing summary.'
    }
}
finally {
    Remove-GateContainers
    Pop-Location
}

$summaryRaw = Get-Content -Raw -Encoding UTF8 -LiteralPath $summaryPath
foreach ($forbidden in @(
        'API Key', 'private key', 'authorization:', 'data:image', 'base64',
        'filename', 'ocrText', 'modelOutput', 'rootDocument', 'chain-of-thought')) {
    if ($summaryRaw.IndexOf($forbidden, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        throw 'IMAGE_ONLY P2 OCR sidecar summary is not payload-free.'
    }
}
$summary = $summaryRaw | ConvertFrom-Json
if ($summary.result -cne 'PASS' -or
        $summary.capabilityId -cne 'rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1' -or
        -not [bool]$summary.offlineBuild -or
        -not [bool]$summary.networkModeNone -or
        -not [bool]$summary.readOnlyRootfs -or
        -not [bool]$summary.nonRoot -or
        -not [bool]$summary.capabilitiesDropped -or
        [int]$summary.pidLimit -ne 64 -or
        -not [bool]$summary.startupProbeBlocking -or
        -not [bool]$summary.syntheticProbeFixedOutput -or
        -not [bool]$summary.r0EquivalenceByteIdentical -or
        [bool]$summary.capabilityAdmitted -or
        $summary.licenseJudgement -cne 'J0_PENDING' -or
        [int]$summary.openAuthorizationCount -ne 0 -or
        [int]$summary.verificationProviderUsage.attempts -ne 0 -or
        [int]$summary.verificationProviderUsage.apiKeyReads -ne 0 -or
        [bool]$summary.productionConfigured -or
        [bool]$summary.productionLiveAuthorityGranted -or
        [bool]$summary.candidateApplied -or
        [bool]$summary.staticSchemaPublished -or
        [bool]$summary.productionDeployed -or
        -not [bool]$summary.payloadFree) {
    throw 'IMAGE_ONLY P2 OCR sidecar summary contract failed.'
}

Write-Host 'IMAGE_ONLY P2 OCR sidecar gate: PASS'
Write-Host "Summary: $summaryPath"
