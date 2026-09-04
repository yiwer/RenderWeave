[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [Parameter(Mandatory = $true)][string]$InputDirectory
)

$ErrorActionPreference = 'Stop'
# Historical exact replay markers retained for independent post-close verification:
# IMAGE_ONLY_V51_PROFILE_ID; 20260818-iopa-v51-diagnostic-7d929b74
# 7d929b74-47ca-40a7-bfd5-061e070c2bd2
# renderweave-image-only-fresh-normalization/1.0:632c601ccdcbd561fcb9502777a888712a564a81352f9f19b163b4a0e9a6b4cc
# RENDERWEAVE_RUN_V51_PROFILE_SUCCESSOR_DIAGNOSTIC
# PROFILE_SUCCESSOR_AUTHORIZATION_ALREADY_EXECUTED; automatic rerun is forbidden.
throw 'IMAGE_ONLY v51 diagnostic authorization is CLOSED; automatic rerun is forbidden.'
