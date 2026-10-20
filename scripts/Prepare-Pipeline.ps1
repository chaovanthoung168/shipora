[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$pipelinePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'infrastructure/pipeline'
foreach ($name in @('.env', 'worker.yaml', 'project.yaml')) {
    $sourceName = if ($name -eq '.env') { '.env.example' } else { $name.Replace('.yaml', '.example.yaml') }
    $destination = Join-Path $pipelinePath $name
    if (-not (Test-Path -LiteralPath $destination)) {
        Copy-Item -LiteralPath (Join-Path $pipelinePath $sourceName) -Destination $destination
    }
}
foreach ($name in @('registry', 'ssh')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $pipelinePath $name) | Out-Null
}
$registryConfig = Join-Path $pipelinePath 'registry/config.json'
if (-not (Test-Path -LiteralPath $registryConfig)) {
    '{"auths":{}}' | Set-Content -LiteralPath $registryConfig -Encoding utf8
}
Write-Host 'Prepared local configuration without replacing existing files.'
Write-Host 'Edit infrastructure/pipeline/.env, worker.yaml and project.yaml. See docs/deployment-pipeline.md.'
