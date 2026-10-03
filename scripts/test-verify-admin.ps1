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
        [int]$TimeoutSec
    )

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
    if ($Uri -match '/sites$' -and $Method -eq 'Post') {
        $script:siteName = ([Text.Encoding]::UTF8.GetString($Body) | ConvertFrom-Json).siteName
        return [pscustomobject]@{ success = $true; data = [pscustomobject]@{ id = 77 } }
    }
    if ($Uri -match '/sites/77$' -and $Method -eq 'Get') {
        return [pscustomobject]@{ success = $true; data = [pscustomobject]@{ siteName = $script:siteName } }
    }
    if ($Uri -match '/sites/77$' -and $Method -eq 'Delete') {
        $script:deleted = $true
        return [pscustomobject]@{ success = $true }
    }
    if ($Uri -match '/auth/logout$' -and $Method -eq 'Post') {
        if (-not $script:deleted) { throw 'Logout preceded temporary site cleanup.' }
        $script:loggedOut = $true
        return [pscustomobject]@{ success = $true }
    }
    throw "Unexpected mocked request: $Method $Uri"
}

function Invoke-Case {
    param([int]$ReusedStatus, [bool]$ExpectSuccess)

    $script:reusedStatus = $ReusedStatus
    $script:loggedOut = $false
    $script:deleted = $false
    $script:siteName = $null
    $failed = $false
    try {
        . (Join-Path $PSScriptRoot 'verify-admin.ps1') -BaseUrl 'http://127.0.0.1:13000'
    }
    catch {
        $failed = $true
        if ($ExpectSuccess) { throw }
    }

    if ($failed -eq $ExpectSuccess) { throw "Unexpected verifier outcome for reused status $ReusedStatus." }
    if (-not $script:deleted -or -not $script:loggedOut) {
        throw "Cleanup or logout was skipped for reused status $ReusedStatus."
    }
}

Invoke-Case -ReusedStatus 401 -ExpectSuccess $true
Invoke-Case -ReusedStatus 200 -ExpectSuccess $false
Invoke-Case -ReusedStatus 403 -ExpectSuccess $false
Invoke-Case -ReusedStatus 500 -ExpectSuccess $false
Write-Host "Admin smoke verifier tests passed."
