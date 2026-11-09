$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
# Keep named volumes so locally seeded benchmark data remains available.
docker compose down
