[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    if ($AsSecureString) {
        return ConvertTo-SecureString "test-only-password" -AsPlainText -Force
    }
    return "test-admin"
}

function Invoke-RestMethod {
    param(
        [string]$Uri,
        [string]$Method,
        [hashtable]$Headers,
        [string]$ContentType,
        [byte[]]$Body,
        [int]$TimeoutSec,
        [int]$MaximumRedirection
    )

    if (-not $PSBoundParameters.ContainsKey('MaximumRedirection') -or $MaximumRedirection -ne 0) {
        throw 'Redirects must be disabled for credential and token requests.'
    }

    if ($Uri -match '/auth/login$' -and $Method -eq 'Post') {
        return [pscustomobject]@{ success = $true; data = [pscustomobject]@{ accessToken = 'test-only-token' } }
    }
    if ($Uri -match '/sites$' -and $Method -eq 'Get') {
        if (-not $script:loggedOut) { return [pscustomobject]@{ success = $true; data = @() } }
        if ($script:reusedStatus -ne 200) {
            $exception = New-Object System.Exception('Simulated API error response')
            Add-Member -InputObject $exception -NotePropertyName Response -NotePropertyValue ([pscustomobject]@{ StatusCode = $script:reusedStatus })
            throw $exception
        }
        return [pscustomobject]@{ success = $true; data = @() }
    }
    if ($Uri -match '/auth/logout$' -and $Method -eq 'Post') {
        $script:loggedOut = $true
        return [pscustomobject]@{ success = $true }
    }
    throw "Unexpected mocked request: $Method $Uri"
}

function Invoke-Case {
    param([int]$ReusedStatus, [bool]$ExpectSuccess)

    $script:reusedStatus = $ReusedStatus
    $script:loggedOut = $false
    $failed = $false
    try {
        . (Join-Path $PSScriptRoot 'verify-admin.ps1') -BaseUrl 'http://127.0.0.1:13000'
    }
    catch {
        $failed = $true
        if ($ExpectSuccess) { throw }
    }

    if ($failed -eq $ExpectSuccess) { throw "Unexpected verifier outcome for reused status $ReusedStatus." }
    if (-not $script:loggedOut) {
        throw "Logout was skipped for reused status $ReusedStatus."
    }
}

function Assert-UnsafeBaseUrlRejected {
    param([string]$BaseUrl)

    $script:loggedOut = $false
    $rejected = $false
    try {
        . (Join-Path $PSScriptRoot 'verify-admin.ps1') -BaseUrl $BaseUrl
    }
    catch {
        if ($_.Exception.Message -match 'BaseUrl must be HTTPS or loopback HTTP') {
            $rejected = $true
        }
        else { throw }
    }
    if (-not $rejected -or $script:loggedOut) {
        throw "Unsafe BaseUrl was not rejected before authentication."
    }
}

Invoke-Case -ReusedStatus 401 -ExpectSuccess $true
Invoke-Case -ReusedStatus 200 -ExpectSuccess $false
Invoke-Case -ReusedStatus 403 -ExpectSuccess $false
Invoke-Case -ReusedStatus 500 -ExpectSuccess $false
Assert-UnsafeBaseUrlRejected -BaseUrl 'http://example.com'
Assert-UnsafeBaseUrlRejected -BaseUrl 'https://example.com/other-path'
Assert-UnsafeBaseUrlRejected -BaseUrl 'https://user:password@example.com'
Write-Host "Admin smoke verifier tests passed."
