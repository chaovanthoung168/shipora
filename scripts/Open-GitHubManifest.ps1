param(
    [Parameter(Mandatory = $true)]
    [string]$ResponseFile
)
$ErrorActionPreference = 'Stop'
$taskResponse = Get-Content -Raw -LiteralPath $ResponseFile | ConvertFrom-Json
$taskAction = [uri]$taskResponse.actionUrl
if ($taskAction.Scheme -ne 'https' -or $taskAction.Host -ne 'github.com' -or
    $taskAction.UserInfo -or $taskAction.Fragment -or
    $taskAction.AbsolutePath -notmatch '^/(settings/apps/new|organizations/[A-Za-z0-9-]+/settings/apps/new)$' -or
    $taskResponse.method -ne 'POST' -or -not $taskResponse.formFields.manifest) {
    throw 'Expected the JSON response from POST /api/git/setup/github.'
}
# Manifest JSON and state are temporary setup data. Credentials are never included.
$taskEncodedAction = [System.Net.WebUtility]::HtmlEncode($taskAction.AbsoluteUri)
$taskEncodedManifest = [System.Net.WebUtility]::HtmlEncode([string]$taskResponse.formFields.manifest)
$taskHtml = @"
<!doctype html>
<html lang="en"><meta charset="utf-8"><title>Shipora GitHub setup</title>
<body><form method="post" action="$taskEncodedAction">
<input type="hidden" name="manifest" value="$taskEncodedManifest">
<button type="submit">Continue to GitHub setup</button>
</form></body></html>
"@
$taskFormPath = Join-Path ([System.IO.Path]::GetTempPath()) ("shipora-github-setup-" + [guid]::NewGuid().ToString('N') + '.html')
Set-Content -LiteralPath $taskFormPath -Value $taskHtml -Encoding utf8
Start-Process -FilePath $taskFormPath
Write-Output 'Continue in the browser, then return to Postman. Delete the temporary form after setup.'
Write-Output $taskFormPath
