[CmdletBinding()]
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$repositoryPath = Split-Path -Parent $PSScriptRoot
foreach ($service in @('project-service', 'application-service', 'git-service', 'deployment-service', 'deployment-worker')) {
    $servicePath = Join-Path $repositoryPath "microservice/$service"
    $arguments = @('-p', $servicePath, 'bootJar', '--no-daemon')
    if ($Offline) { $arguments += '--offline' }
    & (Join-Path $servicePath 'gradlew.bat') @arguments
    if ($LASTEXITCODE -ne 0) { throw "JAR build failed: $service" }
    $dockerfile = if ($service -eq 'deployment-worker') { 'worker.Dockerfile' } else { 'service.Dockerfile' }
    $dockerArguments = @('build', '-f', (Join-Path $repositoryPath "infrastructure/pipeline/$dockerfile"), '-t', "shipora/${service}:local")
    if ($service -ne 'deployment-worker') { $dockerArguments += @('--build-arg', "SERVICE=$service") }
    & docker @dockerArguments $repositoryPath
    if ($LASTEXITCODE -ne 0) { throw "Docker image build failed: $service" }
}
Write-Host 'Built five local images. Nothing was published or deployed.'
