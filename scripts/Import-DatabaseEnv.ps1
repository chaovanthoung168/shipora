[CmdletBinding()]
param(
    [string]$Path = (Join-Path $PSScriptRoot '../infrastructure/database/.env')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    throw "Database environment file not found: $Path"
}

$allowedNames = @(
    'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
    'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_PASSWORD'
)
$settings = @{}

foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -notmatch '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
        continue
    }

    $name = $matches[1]
    $value = $matches[2].Trim()
    if ($allowedNames -cnotcontains $name) {
        continue
    }

    if ($value.StartsWith('"') -or $value.StartsWith("'")) {
        $quote = $value.Substring(0, 1)
        if ($value.Length -lt 2 -or -not $value.EndsWith($quote)) {
            throw "Use a complete, single-line quoted value for $name in the database environment file."
        }
        $value = $value.Substring(1, $value.Length - 2)
    }
    else {
        $value = ($value -replace '\s+#.*$', '').TrimEnd()
    }

    # Treat values as literal data; never execute or interpolate their contents.
    $settings[$name] = $value
}

foreach ($name in $settings.Keys) {
    [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
}

foreach ($pair in @(
    @('DB_NAME', 'POSTGRES_DB'),
    @('DB_USER', 'POSTGRES_USER'),
    @('DB_PASSWORD', 'POSTGRES_PASSWORD')
)) {
    $primary = [Environment]::GetEnvironmentVariable($pair[0], 'Process')
    $fallback = [Environment]::GetEnvironmentVariable($pair[1], 'Process')
    if ([string]::IsNullOrEmpty($primary) -and [string]::IsNullOrEmpty($fallback)) {
        throw "Configure $($pair[0]) or $($pair[1]) before starting a service."
    }
}

Write-Host 'Database environment variables loaded for this PowerShell session.'
