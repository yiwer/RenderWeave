[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [Parameter(Mandatory = $true)][string]$InputDirectory
)

$ErrorActionPreference = 'Stop'
# Historical exact replay markers retained for independent post-close verification:
# IMAGE_ONLY_V52_PROFILE_ID; 20260818-iopa-v52-diagnostic-981d7262
# 981d7262-d802-45bb-96ce-d34b4468f9f9
# renderweave-image-only-fresh-normalization/1.0:e0e505c515ff3c7c7bac57e0ddc19e714721e301fd2216830bc6ac82f98cae35
# RENDERWEAVE_RUN_V52_PROFILE_SUCCESSOR_DIAGNOSTIC
# PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED; automatic rerun is forbidden.
throw 'IMAGE_ONLY v52 diagnostic authorization is CLOSED; automatic rerun is forbidden.'
