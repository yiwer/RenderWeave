[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [Parameter(Mandatory = $true)][string]$InputDirectory
)

$ErrorActionPreference = 'Stop'
# Historical exact replay markers retained for independent post-close verification:
# IMAGE_ONLY_V50_PROFILE_ID; 20260818-iopa-v50-diagnostic-82f1d86b
# 82f1d86b-065b-4357-924e-19945daf1077
# renderweave-image-only-fresh-normalization/1.0:146c27620edad71fd40618772c3c1fc8613684d83b91bf20edc5d944b7a4b8b4
# RENDERWEAVE_RUN_V50_PROFILE_SUCCESSOR_DIAGNOSTIC
# PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED; automatic rerun is forbidden.
throw 'IMAGE_ONLY v50 diagnostic authorization is CLOSED; automatic rerun is forbidden.'
