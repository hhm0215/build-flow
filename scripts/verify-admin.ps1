[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:3000"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$loginId = Read-Host "Admin login ID"
$securePassword = Read-Host "Admin password" -AsSecureString
$passwordPointer = [IntPtr]::Zero
$plainPassword = $null
$loginBody = $null
$siteBody = $null
$headers = $null
$accessToken = $null
$siteId = $null
$cleanupFailed = $false
$verified = $false
$apiUrl = "$($BaseUrl.TrimEnd('/'))/api/v1"

try {
    if ($loginId -cnotmatch '^[A-Za-z0-9._-]{3,50}$') {
        throw "Admin login ID format is invalid."
    }

    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $loginBody = [Text.Encoding]::UTF8.GetBytes((@{
        loginId = $loginId
        password = $plainPassword
    } | ConvertTo-Json -Compress))

    $login = Invoke-RestMethod -Uri "$apiUrl/auth/login" -Method Post -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 15
    if (-not $login.success -or [string]::IsNullOrWhiteSpace($login.data.accessToken)) {
        throw "Admin login did not return an access token."
    }

    $accessToken = $login.data.accessToken
    $headers = @{ Authorization = "Bearer $accessToken" }
    $sites = Invoke-RestMethod -Uri "$apiUrl/sites" -Method Get -Headers $headers -TimeoutSec 15
    if (-not $sites.success) {
        throw "Authenticated site list request failed."
    }

    $siteName = "BuildFlow smoke $([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))"
    $siteBody = [Text.Encoding]::UTF8.GetBytes((@{ siteName = $siteName } | ConvertTo-Json -Compress))
    $created = Invoke-RestMethod -Uri "$apiUrl/sites" -Method Post -Headers $headers -ContentType "application/json; charset=utf-8" -Body $siteBody -TimeoutSec 15
    if (-not $created.success -or $null -eq $created.data.id) {
        throw "Temporary site creation failed."
    }
    $siteId = [long]$created.data.id

    $detail = Invoke-RestMethod -Uri "$apiUrl/sites/$siteId" -Method Get -Headers $headers -TimeoutSec 15
    if (-not $detail.success -or $detail.data.siteName -ne $siteName) {
        throw "Temporary site detail did not match."
    }

    $verified = $true
}
finally {
    if ($null -ne $siteId) {
        try {
            Invoke-RestMethod -Uri "$apiUrl/sites/$siteId" -Method Delete -Headers $headers -TimeoutSec 15 | Out-Null
        }
        catch {
            $cleanupFailed = $true
            Write-Warning "Temporary test site $siteId could not be removed; please inspect it before further testing."
        }
    }

    if ($null -ne $loginBody) { [Array]::Clear($loginBody, 0, $loginBody.Length) }
    if ($null -ne $siteBody) { [Array]::Clear($siteBody, 0, $siteBody.Length) }
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $plainPassword = $null
    $accessToken = $null
    $headers = $null
}

if ($cleanupFailed) { throw "Smoke test passed but temporary site cleanup failed." }
if ($verified) { Write-Host "Admin login and site API smoke test passed; temporary site was removed." }
