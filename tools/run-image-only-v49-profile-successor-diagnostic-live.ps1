[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [Parameter(Mandatory = $true)][string]$InputDirectory
)

$ErrorActionPreference = 'Stop'
# Historical exact replay markers retained for the v49 independent post-close verifier:
# IMAGE_ONLY_V49_PROFILE_ID; 20260818-iopa-v49-diagnostic-432fdfeb
# 432fdfeb-c5ab-4cff-92f4-e066a0d98c8c
# renderweave-image-only-fresh-normalization/1.0:3096deba42aeab03be175074e6717ccf6898d4a628950d19eaa6891674d62375
# RENDERWEAVE_RUN_V49_PROFILE_SUCCESSOR_DIAGNOSTIC
# PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED; automatic rerun is forbidden.
throw 'IMAGE_ONLY v49 diagnostic authorization is CLOSED; automatic rerun is forbidden.'
