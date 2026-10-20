[CmdletBinding()]
param(
    [string]$DatabaseEnvPath = (Join-Path $PSScriptRoot '../infrastructure/database/.env'),
    [string]$ApiUsername = 'developer',
    [ValidateRange(1, 65535)][int]$Port = 8083,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$existingListener = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($existingListener.Count -gt 0) {
    $ownerIds = ($existingListener.OwningProcess | Sort-Object -Unique) -join ', '
    throw "Port $Port is already in use by process $ownerIds. Stop the existing Deployment Service in IntelliJ or its terminal before running this launcher. Use only one instance."
}
if ([string]::IsNullOrWhiteSpace($env:LOCAL_API_PASSWORD)) {
    throw 'Set LOCAL_API_PASSWORD to the same API password used by the other local services before starting.'
}
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$servicePath = Join-Path $repositoryRoot 'microservice/deployment-service'
. (Join-Path $PSScriptRoot 'Import-DatabaseEnv.ps1') -Path $DatabaseEnvPath
$env:LOCAL_API_USERNAME = $ApiUsername
$env:SPRING_PROFILES_ACTIVE = 'local'

# The HTTP client needs the same Windows socket workaround as Gradle.
$socketPath = Join-Path $repositoryRoot '.gradle/git-sockets'
New-Item -ItemType Directory -Path $socketPath -Force | Out-Null
if ($env:JAVA_TOOL_OPTIONS -notmatch 'jdk.net.unixdomain.tmpdir') {
    $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' -Djdk.net.unixdomain.tmpdir=' + ($socketPath -replace '\\', '/')).Trim()
}
if ($env:JAVA_TOOL_OPTIONS -notmatch 'java.net.preferIPv4Stack') {
    $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' -Djava.net.preferIPv4Stack=true').Trim()
}
if (-not $SkipBuild) {
    & (Join-Path $servicePath 'gradlew.bat') -p $servicePath bootJar --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Deployment Service build failed.' }
}
$jar = Get-ChildItem -LiteralPath (Join-Path $servicePath 'build/libs') -Filter '*.jar' |
    Where-Object Name -NotLike '*-plain.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw 'Build the Deployment Service first, or run without -SkipBuild.' }
Write-Host "Starting Deployment Service on 127.0.0.1:$Port with the local profile."
& java -jar $jar.FullName --spring.profiles.active=local "--server.port=$Port" --server.address=127.0.0.1
exit $LASTEXITCODE
