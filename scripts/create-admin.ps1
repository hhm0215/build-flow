[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$loginId = Read-Host "Admin login ID (3-50 letters, digits, ., _, -)"
$name = Read-Host "Admin display name"
$securePassword = Read-Host "Admin password (minimum 9 characters)" -AsSecureString
$passwordPointer = [IntPtr]::Zero
$plainPassword = $null
$payload = $null
$body = $null
$process = $null

try {
    if ($loginId -cnotmatch '^[A-Za-z0-9._-]{3,50}$') {
        throw "Admin login ID must be 3-50 letters, digits, ., _, or -."
    }
    if ([string]::IsNullOrWhiteSpace($name) -or $name.Length -gt 50) {
        throw "Admin display name must be 1-50 characters."
    }

    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if ($plainPassword.Length -lt 9) {
        throw "Admin password must contain at least 9 characters."
    }

    $payload = @{
        loginId = $loginId
        name = $name
        password = $plainPassword
    } | ConvertTo-Json -Compress
    $body = [Text.Encoding]::UTF8.GetBytes($payload + "`n")

    $workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = "docker"
    $startInfo.Arguments = "compose -f docker-compose.yml -f docker-compose.app.yml exec -T auth-service java -jar /app/app.jar --spring.main.web-application-type=none --eureka.client.enabled=false --app.admin-bootstrap.enabled=true"
    $startInfo.WorkingDirectory = $workspace
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Could not start the local admin setup command."
    }
    $process.StandardInput.BaseStream.Write($body, 0, $body.Length)
    $process.StandardInput.BaseStream.Flush()
    $process.StandardInput.Close()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Admin setup failed. Check that the stack is running and no admin account already exists."
    }
}
finally {
    if ($null -ne $body) {
        [Array]::Clear($body, 0, $body.Length)
    }
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ($null -ne $process) {
        $process.Dispose()
    }
    $body = $null
    $payload = $null
    $plainPassword = $null
    $securePassword = $null
}
