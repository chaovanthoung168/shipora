[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^ghcr\.io/[a-z0-9][a-z0-9_.-]*/[a-z0-9][a-z0-9_./-]*$')][string]$ImageRepository,
    [switch]$PrepareOnly,
    [switch]$BuildkitOnly,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$localPath = Join-Path $repositoryRoot '.shipora/local-worker'
$compose = Join-Path $repositoryRoot 'infrastructure/pipeline/compose.local-worker.yaml'
foreach ($folder in @($localPath, (Join-Path $localPath 'registry'), (Join-Path $localPath 'ssh'))) {
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
}
$workerEnv = Join-Path $localPath 'worker.env'
$serviceConfig = Join-Path $localPath 'service-worker.properties'
if (-not (Test-Path -LiteralPath $workerEnv)) {
    $workerSecret = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    @("LOCAL_WORKER_USERNAME=shipora-worker", "LOCAL_WORKER_PASSWORD=$workerSecret", "SHIPORA_IMAGE_REPOSITORY=$ImageRepository") | Set-Content -LiteralPath $workerEnv -Encoding utf8
}
$workerValues = @{}
foreach ($line in Get-Content -LiteralPath $workerEnv) {
    if ($line -match '^(LOCAL_WORKER_USERNAME|LOCAL_WORKER_PASSWORD|SHIPORA_IMAGE_REPOSITORY)=(.*)$') { $workerValues[$matches[1]] = $matches[2] }
}
if ($workerValues['SHIPORA_IMAGE_REPOSITORY'] -ne $ImageRepository) { throw 'Existing worker.env uses another image repository. Review that ignored file before changing installations.' }
if ([string]::IsNullOrWhiteSpace($workerValues['LOCAL_WORKER_PASSWORD'])) { throw 'Configure a dedicated worker password in the ignored worker.env.' }
if (-not (Test-Path -LiteralPath $serviceConfig)) {
    @("shipora.local.worker-username=$($workerValues['LOCAL_WORKER_USERNAME'])", "shipora.local.worker-password=$($workerValues['LOCAL_WORKER_PASSWORD'])") | Set-Content -LiteralPath $serviceConfig -Encoding utf8
}
$workerConfig = Join-Path $localPath 'worker.yaml'
if (-not (Test-Path -LiteralPath $workerConfig)) { Copy-Item -LiteralPath (Join-Path $repositoryRoot 'infrastructure/pipeline/worker.example.yaml') -Destination $workerConfig }
$registryPath = Join-Path $localPath 'registry/config.json'
if (-not (Test-Path -LiteralPath $registryPath)) {
    # Export only the authorized registry entry; never copy the personal Docker config.
    $dockerConfigPath = Join-Path $env:USERPROFILE '.docker/config.json'
    if (-not (Test-Path -LiteralPath $dockerConfigPath)) { throw 'Sign in to ghcr.io using Docker first, then run this script again.' }
    $dockerConfig = Get-Content -LiteralPath $dockerConfigPath -Raw | ConvertFrom-Json
    $auth = $dockerConfig.auths.'ghcr.io'.auth
    if (-not $auth) {
        $helperName = $dockerConfig.credHelpers.'ghcr.io'
        if (-not $helperName) { $helperName = $dockerConfig.credsStore }
        if ($helperName -notmatch '^[a-zA-Z0-9_-]+$') { throw 'A supported GHCR credential helper or direct auth entry is required.' }
        $helper = Get-Command "docker-credential-$helperName" -ErrorAction Stop
        $savedCredential = 'ghcr.io' | & $helper.Source get | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or -not $savedCredential.Secret) { throw 'No GHCR login is available. Sign in with docker login ghcr.io and retry.' }
        $auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($savedCredential.Username):$($savedCredential.Secret)"))
    }
    @{ auths = @{ 'ghcr.io' = @{ auth = $auth } } } | ConvertTo-Json -Depth 5 -Compress | Set-Content -LiteralPath $registryPath -Encoding utf8
}
Write-Host "Prepared private local worker configuration in $localPath."
Write-Host "For each local Project, Application, Git and Deployment API, add this program argument and restart with its existing settings:"
Write-Host "--spring.config.additional-location=file:$($serviceConfig -replace '\\','/')"
if ($PrepareOnly) { return }
& docker compose -p shipora-local-worker -f $compose up -d buildkit
if ($LASTEXITCODE -ne 0) { throw 'BuildKit startup failed.' }
if ($BuildkitOnly) { Write-Host 'BuildKit is started. The deployment worker has not been enabled.'; return }
# Verify the dedicated worker identity without claiming or mutating any real job.
$authorization = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($workerValues['LOCAL_WORKER_USERNAME']):$($workerValues['LOCAL_WORKER_PASSWORD'])"))
$missingJob = '00000000-0000-4000-8000-000000000000'
$body = @{ leaseToken = $missingJob } | ConvertTo-Json -Compress
foreach ($entry in @(@(8081,'server-context'), @(8082,'application-context'), @(8084,'source-context'), @(8083,'authorizations'))) {
    $port = [int]$entry[0]
    $route = if ($port -eq 8083) { "/internal/deployment-jobs/$missingJob/authorizations" } else { "/internal/worker/jobs/$missingJob/$($entry[1])" }
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port$route" -Method Post -Headers @{ Authorization="Basic $authorization"; 'X-Shipora-Request'='true' } -ContentType 'application/json' -Body $body -SkipHttpErrorCheck -TimeoutSec 8
    if ([int]$response.StatusCode -ne 404) { throw "Service on port $port has not accepted the configured worker identity. Restart it with the private additional configuration printed above before enabling processing." }
}
if (-not $SkipBuild) {
    $socketPath = Join-Path $repositoryRoot '.gradle/worker-sockets'
    New-Item -ItemType Directory -Force -Path $socketPath | Out-Null
    if ($env:JAVA_TOOL_OPTIONS -notmatch 'jdk.net.unixdomain.tmpdir') { $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$($socketPath -replace '\\','/')" }
    & (Join-Path $repositoryRoot 'microservice/deployment-worker/gradlew.bat') -p (Join-Path $repositoryRoot 'microservice/deployment-worker') bootJar --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Worker build failed.' }
    & docker compose -p shipora-local-worker -f $compose build deployment-worker
    if ($LASTEXITCODE -ne 0) { throw 'Worker image build failed.' }
}
& docker compose -p shipora-local-worker -f $compose up -d deployment-worker
if ($LASTEXITCODE -ne 0) { throw 'Worker startup failed.' }
