<#
.SYNOPSIS
  Phase E1: create or refresh per-service MySQL runtime accounts (buildflow_<svc>@'%').

.DESCRIPTION
  Each account receives ALL PRIVILEGES on its own schema only (DDL included, because
  services still run Hibernate ddl-auto=update) and no global or cross-schema privilege.
  Re-running is safe: CREATE USER IF NOT EXISTS, password reset to the supplied value,
  all privileges revoked and the single schema grant re-applied.

  Passwords are never written to disk or passed on a command line. They are read from
  environment variables <SVC>_DB_PASSWORD (e.g. SITE_DB_PASSWORD) of this process, or
  from -EnvFile (KEY=VALUE lines such as the repository .env), and sent to mysql via stdin.
  The MySQL root password is taken from the target container's own MYSQL_ROOT_PASSWORD.

  Existing services keep using root until <SVC>_DB_USERNAME/<SVC>_DB_PASSWORD are set
  for docker-compose.app.yml (opt-in).
#>
param(
    [string]$ContainerName = "buildflow-mysql",
    [ValidateSet("auth", "estimate", "site", "purchase", "tax", "notification", "chat")]
    [string[]]$Service = @("auth", "estimate", "site", "purchase", "tax", "notification", "chat"),
    [string]$EnvFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$minimumPasswordLength = 16

function Read-EnvFileValues {
    param([Parameter(Mandatory = $true)][string]$Path)
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "EnvFile not found: $Path" }
    foreach ($line in [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).ProviderPath)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) { continue }
        $index = $trimmed.IndexOf("=")
        if ($index -lt 1) { continue }
        $key = $trimmed.Substring(0, $index).Trim()
        if ($key -notmatch '^[A-Z]+_DB_PASSWORD$') { continue }
        $value = $trimmed.Substring($index + 1)
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }
    return $values
}

function ConvertTo-SqlStringLiteral {
    param([Parameter(Mandatory = $true)][string]$Value)
    return "'" + $Value.Replace("\", "\\").Replace("'", "''") + "'"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker is required and must be running." }
if ($ContainerName -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') { throw "Invalid container name." }

$fileValues = @{}
if ($EnvFile) { $fileValues = Read-EnvFileValues -Path $EnvFile }

# Validate every password before touching the database (all-or-nothing input check).
$accounts = @()
$secrets = @()
foreach ($svc in $Service) {
    $key = "$($svc.ToUpperInvariant())_DB_PASSWORD"
    $password = [Environment]::GetEnvironmentVariable($key)
    if ([string]::IsNullOrEmpty($password) -and $fileValues.ContainsKey($key)) { $password = $fileValues[$key] }
    if ([string]::IsNullOrEmpty($password)) { throw "$key is not set (environment or -EnvFile). No account was changed." }
    if ($password.Length -lt $minimumPasswordLength) { throw "$key must be at least $minimumPasswordLength characters. No account was changed." }
    if ($password -match '[\x00-\x1F\x7F]') { throw "$key contains control characters. No account was changed." }
    $accounts += [pscustomobject]@{ Service = $svc; User = "buildflow_$svc"; Schema = "buildflow_$svc"; Password = $password }
    $secrets += $password
}

$builder = New-Object System.Text.StringBuilder
foreach ($account in $accounts) {
    $userRef = "'$($account.User)'@'%'"
    $literal = ConvertTo-SqlStringLiteral -Value $account.Password
    [void]$builder.AppendLine("CREATE DATABASE IF NOT EXISTS ``$($account.Schema)`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
    [void]$builder.AppendLine("CREATE USER IF NOT EXISTS $userRef IDENTIFIED BY $literal;")
    [void]$builder.AppendLine("ALTER USER $userRef IDENTIFIED BY $literal;")
    [void]$builder.AppendLine("REVOKE ALL PRIVILEGES, GRANT OPTION FROM $userRef;")
    [void]$builder.AppendLine("GRANT ALL PRIVILEGES ON ``$($account.Schema)``.* TO $userRef;")
}
$names = ($accounts | ForEach-Object { "'$($_.User)'" }) -join ","
[void]$builder.AppendLine("SELECT CONCAT(user, '@', host) FROM mysql.user WHERE user IN ($names) ORDER BY user;")
$sql = $builder.ToString()

$mysqlShell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names --raw'
$process = New-Object System.Diagnostics.Process
$process.StartInfo = New-NativeProcessStartInfo -FileName "docker" -Arguments @("exec", "-i", $ContainerName, "sh", "-c", $mysqlShell) -WorkingDirectory $PSScriptRoot
$process.StartInfo.RedirectStandardInput = $true
if (-not $process.Start()) { throw "Could not start docker." }
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$process.StandardInput.Write($sql)
$process.StandardInput.Close()
$process.WaitForExit()
$stdout = $stdoutTask.GetAwaiter().GetResult()
$stderr = $stderrTask.GetAwaiter().GetResult()
$sql = $null
foreach ($secret in $secrets) { if ($secret) { $stderr = $stderr.Replace($secret, "***") } }
if ($process.ExitCode -ne 0) {
    throw "mysql in container '$ContainerName' failed with exit code $($process.ExitCode): $($stderr.Trim())"
}

$present = @(Get-OutputLines -Text $stdout)
foreach ($account in $accounts) {
    if ($present -notcontains "$($account.User)@%") { throw "Account $($account.User)@% was not found after apply." }
}
Write-Output "Service accounts ready in '$ContainerName' (own-schema ALL only): $($present -join ', ')"
