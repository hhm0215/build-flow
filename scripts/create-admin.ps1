[CmdletBinding()]
param(
    [string]$GatewayUrl = "http://localhost:8080"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$email = Read-Host "Admin email"
$name = Read-Host "Admin display name"
$securePassword = Read-Host "Admin password (minimum 8 characters)" -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$plainPassword = $null
$payload = $null
$body = $null

try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if ($plainPassword.Length -lt 8) {
        throw "Password must contain at least 8 characters."
    }

    $payload = @{
        email = $email
        password = $plainPassword
        name = $name
        role = "ADMIN"
    } | ConvertTo-Json -Compress
    $body = [Text.Encoding]::UTF8.GetBytes($payload)

    $created = $false
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        try {
            Invoke-RestMethod `
                -Method Post `
                -Uri "$($GatewayUrl.TrimEnd('/'))/api/v1/auth/signup" `
                -ContentType "application/json; charset=utf-8" `
                -TimeoutSec 5 `
                -Body $body | Out-Null
            $created = $true
            break
        }
        catch {
            $statusCode = $null
            if ($null -ne $_.Exception.Response) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            $retryable = ($null -eq $statusCode) -or ($statusCode -in @(502, 503, 504))
            if (($attempt -eq 6) -or (-not $retryable)) {
                throw
            }
            Start-Sleep -Seconds 5
        }
    }

    if (-not $created) {
        throw "Admin account creation did not complete."
    }
    Write-Host "Admin account created."
}
finally {
    if ($null -ne $body) {
        [Array]::Clear($body, 0, $body.Length)
    }
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $body = $null
    $payload = $null
    $plainPassword = $null
    $securePassword = $null
}
