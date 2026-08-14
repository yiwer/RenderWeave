[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'

# The single approved execution is terminal and must not be retried. The parameter is retained so
# stale automation fails with the stable route-closed code instead of invoking any local or remote
# acquisition implementation. A successor experiment requires a new specification and entry point.
throw 'R5_PRODUCT_TRANSFORM_ROUTE_CLOSED'
