$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
$maven = if ($env:MAVEN_HOME) { Join-Path $env:MAVEN_HOME 'bin\mvn.cmd' } else { 'mvn.cmd' }
& $maven -s '.mvn\settings.xml' test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
