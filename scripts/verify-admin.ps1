[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:3000"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$parsedBaseUrl = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$parsedBaseUrl) -or
    -not [string]::IsNullOrEmpty($parsedBaseUrl.UserInfo) -or
    $parsedBaseUrl.AbsolutePath -ne '/' -or
    -not [string]::IsNullOrEmpty($parsedBaseUrl.Query) -or
    -not [string]::IsNullOrEmpty($parsedBaseUrl.Fragment) -or
    -not ($parsedBaseUrl.Scheme -eq 'https' -or
          ($parsedBaseUrl.Scheme -eq 'http' -and $parsedBaseUrl.IsLoopback))) {
    throw "BaseUrl must be HTTPS or loopback HTTP, without credentials, path, query, or fragment."
}

$loginId = Read-Host "Admin login ID"
$securePassword = Read-Host "Admin password" -AsSecureString
$passwordPointer = [IntPtr]::Zero
$plainPassword = $null
$loginBody = $null
$headers = $null
$accessToken = $null
$verified = $false
$revocationVerified = $false
$revocationFailed = $false
$apiUrl = "$($parsedBaseUrl.AbsoluteUri.TrimEnd('/'))/api/v1"

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

    $login = Invoke-RestMethod -Uri "$apiUrl/auth/login" -Method Post -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 15 -MaximumRedirection 0
    if (-not $login.success -or [string]::IsNullOrWhiteSpace($login.data.accessToken)) {
        throw "Admin login did not return an access token."
    }

    $accessToken = $login.data.accessToken
    $headers = @{ Authorization = "Bearer $accessToken" }
    $sites = Invoke-RestMethod -Uri "$apiUrl/sites" -Method Get -Headers $headers -TimeoutSec 15 -MaximumRedirection 0
    if (-not $sites.success) {
        throw "Authenticated site list request failed."
    }

    $verified = $true
}
finally {
    if ($null -ne $headers) {
        try {
            $logout = Invoke-RestMethod -Uri "$apiUrl/auth/logout" -Method Post -Headers $headers -TimeoutSec 15 -MaximumRedirection 0
            if (-not $logout.success) {
                throw "Admin logout was not confirmed."
            }

            $reusedTokenStatus = $null
            try {
                Invoke-RestMethod -Uri "$apiUrl/sites" -Method Get -Headers $headers -TimeoutSec 15 -MaximumRedirection 0 | Out-Null
                $reusedTokenStatus = 200
            }
            catch {
                if ($null -eq $_.Exception.Response) { throw }
                $reusedTokenStatus = [int]$_.Exception.Response.StatusCode
            }
            if ($reusedTokenStatus -ne 401) {
                throw "Logged-out access token was not rejected with 401 (status: $reusedTokenStatus)."
            }
            $revocationVerified = $true
        }
        catch {
            $revocationFailed = $true
            Write-Warning "Logout or old-token rejection failed; inspect the private pilot before public exposure."
        }
    }

    if ($null -ne $loginBody) { [Array]::Clear($loginBody, 0, $loginBody.Length) }
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $plainPassword = $null
    $accessToken = $null
    $headers = $null
}

if ($revocationFailed) { throw "Admin logout revocation smoke test failed." }
if ($verified -and $revocationVerified) {
    Write-Host "Admin login, authenticated site list, and old-token rejection smoke tests passed; no site was created."
}
