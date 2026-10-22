<#
.SYNOPSIS
  Isolated Docker verification for scripts/db/service-accounts.ps1 (Phase E1).

.DESCRIPTION
  Starts MySQL 8 in a random throw-away Compose project (no host DB port), creates the
  seven service accounts, and asserts: own-schema DDL/DML works, other schemas and
  mysql.user are denied, re-runs are idempotent and remove privilege drift.
  -ValidateSiteService additionally boots the site-service image as buildflow_site and
  waits for /actuator/health UP. Only the random project's containers/volume are removed.
#>
param(
    [switch]$ValidateSiteService,
    [int]$SiteHealthTimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$services = @("auth", "estimate", "site", "purchase", "tax", "notification", "chat")
$composeFile = Join-Path $PSScriptRoot "service-accounts-test.compose.yml"
$accountScript = Join-Path $PSScriptRoot "service-accounts.ps1"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).ProviderPath
$psExe = (Get-Process -Id $PID).Path

function Get-LabelValue {
    param([Parameter(Mandatory = $true)][object]$Labels, [Parameter(Mandatory = $true)][string]$Name)
    $property = $Labels.PSObject.Properties[$Name]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "Required Docker label is missing: $Name"
    }
    return [string]$property.Value
}

function New-RandomSecret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    # Include characters that need SQL escaping to prove the literal handling.
    return ([Convert]::ToBase64String($bytes) -replace '[+/=]', 'x') + "'\q"
}

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [switch]$AllowStandardError)
    return (Invoke-NativeProcess -FileName "docker" -Arguments $Arguments -WorkingDirectory $repoRoot -RejectStandardError:(-not $AllowStandardError)).StandardOutput
}

function Invoke-MySql {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [string]$User,
        [string]$Password
    )
    if ($User) {
        $arguments = @("exec", "-i", "-e", "MYSQL_PWD", $script:containerId, "mysql", "--user=$User", "--host=127.0.0.1", "--protocol=TCP", "--batch", "--skip-column-names")
    } else {
        $arguments = @("exec", "-i", $script:containerId, "sh", "-c", 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names')
    }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-NativeProcessStartInfo -FileName "docker" -Arguments $arguments -WorkingDirectory $repoRoot
    $process.StartInfo.RedirectStandardInput = $true
    if ($User) { $process.StartInfo.EnvironmentVariables["MYSQL_PWD"] = $Password }
    if (-not $process.Start()) { throw "Could not start docker." }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write($Sql)
    $process.StandardInput.Close()
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        StandardOutput = $stdoutTask.GetAwaiter().GetResult()
        StandardError = $stderrTask.GetAwaiter().GetResult()
    }
}

function Assert-Success {
    param([Parameter(Mandatory = $true)][object]$Result, [Parameter(Mandatory = $true)][string]$Label)
    if ($Result.ExitCode -ne 0) { throw "FAIL [$Label]: exit $($Result.ExitCode) $($Result.StandardError.Trim())" }
}

function Assert-Denied {
    param([Parameter(Mandatory = $true)][object]$Result, [Parameter(Mandatory = $true)][string]$Code, [Parameter(Mandatory = $true)][string]$Label)
    if ($Result.ExitCode -eq 0 -or $Result.StandardError -notmatch "ERROR $Code") {
        throw "FAIL [$Label]: expected ERROR $Code, got exit $($Result.ExitCode) $($Result.StandardError.Trim())"
    }
}

function Invoke-AccountScript {
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-NativeProcessStartInfo -FileName $psExe -Arguments @("-NoProfile", "-NonInteractive", "-File", $accountScript, "-ContainerName", $script:containerId) -WorkingDirectory $repoRoot
    if (-not $process.Start()) { throw "Could not start PowerShell." }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        StandardOutput = $stdoutTask.GetAwaiter().GetResult()
        StandardError = $stderrTask.GetAwaiter().GetResult()
    }
}

function Get-AccountGrants {
    $result = Invoke-MySql -Sql "SELECT CONCAT(user,'@',host) FROM mysql.user WHERE user LIKE 'buildflow\_%' ORDER BY user;"
    Assert-Success -Result $result -Label "list accounts"
    $lines = @()
    foreach ($account in @(Get-OutputLines -Text $result.StandardOutput)) {
        $user = $account.Split("@")[0]
        $grants = Invoke-MySql -Sql "SHOW GRANTS FOR '$user'@'%';"
        Assert-Success -Result $grants -Label "grants $user"
        $lines += @(Get-OutputLines -Text $grants.StandardOutput)
    }
    return $lines
}

function Assert-AccountMatrix {
    param([Parameter(Mandatory = $true)][hashtable]$Passwords, [Parameter(Mandatory = $true)][string]$Round)
    $grants = @(Get-AccountGrants)
    $expected = @()
    foreach ($svc in $services) {
        $expected += "GRANT USAGE ON *.* TO ``buildflow_$svc``@``%``"
        $expected += "GRANT ALL PRIVILEGES ON ``buildflow_$svc``.* TO ``buildflow_$svc``@``%``"
    }
    $diff = Compare-Object -ReferenceObject ($expected | Sort-Object) -DifferenceObject ($grants | Sort-Object)
    if ($diff) { throw "FAIL [$Round grants]: $($diff | Out-String)" }
    Write-Output "PASS [$Round] 7 accounts, grants exactly USAGE + own-schema ALL"

    foreach ($svc in $services) {
        $user = "buildflow_$svc"
        $own = "buildflow_$svc"
        $other = if ($svc -eq "auth") { "buildflow_site" } else { "buildflow_auth" }
        $pw = $Passwords[$svc]

        $ddl = Invoke-MySql -User $user -Password $pw -Sql "CREATE DATABASE IF NOT EXISTS $own; USE $own; CREATE TABLE acct_probe (id INT PRIMARY KEY); ALTER TABLE acct_probe ADD COLUMN note VARCHAR(10); CREATE INDEX idx_note ON acct_probe(note); INSERT INTO acct_probe VALUES (1,'ok'); UPDATE acct_probe SET note='ok2'; SELECT COUNT(*) FROM acct_probe; DELETE FROM acct_probe; DROP TABLE acct_probe;"
        Assert-Success -Result $ddl -Label "$Round $user own-schema DDL/DML"
        if ((@(Get-OutputLines -Text $ddl.StandardOutput)) -notcontains "1") { throw "FAIL [$Round $user own-schema SELECT]" }

        Assert-Denied -Result (Invoke-MySql -User $user -Password $pw -Sql "SELECT COUNT(*) FROM $other.acct_root_probe;") -Code "1142" -Label "$Round $user SELECT $other"
        Assert-Denied -Result (Invoke-MySql -User $user -Password $pw -Sql "INSERT INTO $other.acct_root_probe VALUES (2);") -Code "1142" -Label "$Round $user INSERT $other"
        Assert-Denied -Result (Invoke-MySql -User $user -Password $pw -Sql "SELECT COUNT(*) FROM mysql.user;") -Code "1142" -Label "$Round $user SELECT mysql.user"
        Assert-Denied -Result (Invoke-MySql -User $user -Password $pw -Sql "CREATE DATABASE buildflow_acct_forbidden;") -Code "1044" -Label "$Round $user CREATE DATABASE"
        Assert-Denied -Result (Invoke-MySql -User $user -Password $pw -Sql "CREATE USER 'acct_forbidden'@'%' IDENTIFIED BY 'x';") -Code "1227" -Label "$Round $user CREATE USER"

        $dbs = Invoke-MySql -User $user -Password $pw -Sql "SHOW DATABASES;"
        Assert-Success -Result $dbs -Label "$Round $user SHOW DATABASES"
        $visible = @(Get-OutputLines -Text $dbs.StandardOutput | Where-Object { $_ -like "buildflow_*" })
        if ($visible.Count -ne 1 -or $visible[0] -cne $own) { throw "FAIL [$Round $user visible schemas]: $($visible -join ',')" }
    }
    Write-Output "PASS [$Round] own-schema CREATE/ALTER/INDEX/DML/DROP allowed for all 7"
    Write-Output "PASS [$Round] other schema SELECT/INSERT (1142), mysql.user (1142), CREATE DATABASE (1044), CREATE USER (1227) denied for all 7"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker is required and must be running." }

$nonce = [guid]::NewGuid().ToString("N")
$projectName = "bfacct" + (Get-Random -Minimum 100000 -Maximum 999999)
$composeArgs = @("compose", "--project-name", $projectName, "-f", $composeFile)
$passwords = @{}
foreach ($svc in $services) { $passwords[$svc] = New-RandomSecret }
$script:containerId = $null
$started = $false
$completed = $false

$savedEnvironment = @{}
$envNames = @("ACCOUNT_TEST_ROOT_PASSWORD", "ACCOUNT_TEST_NONCE") + ($services | ForEach-Object { "$($_.ToUpperInvariant())_DB_PASSWORD" })
foreach ($name in $envNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }

try {
    if (@(Get-OutputLines -Text (Invoke-Docker -Arguments @("ps", "-aq", "--filter", "label=com.docker.compose.project=$projectName"))).Count -gt 0 -or
        @(Get-OutputLines -Text (Invoke-Docker -Arguments @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$projectName"))).Count -gt 0) {
        throw "Random project $projectName already has resources; rerun."
    }

    $env:ACCOUNT_TEST_ROOT_PASSWORD = (New-RandomSecret) -replace "['\\]", "r"
    $env:ACCOUNT_TEST_NONCE = $nonce
    foreach ($svc in $services) { [Environment]::SetEnvironmentVariable("$($svc.ToUpperInvariant())_DB_PASSWORD", $null) }

    Write-Output "Isolated project: $projectName"
    $started = $true
    [void](Invoke-Docker -Arguments ($composeArgs + @("up", "-d", "--wait", "mysql")) -AllowStandardError)
    $script:containerId = (Invoke-Docker -Arguments ($composeArgs + @("ps", "-q", "mysql"))).Trim()
    $container = (Invoke-Docker -Arguments @("inspect", $script:containerId)) | ConvertFrom-Json
    if ((Get-LabelValue -Labels $container.Config.Labels -Name "com.docker.compose.project") -cne $projectName -or
        (Get-LabelValue -Labels $container.Config.Labels -Name "com.buildflow.account-test") -cne $nonce -or
        $container.Name -like "*buildflow-mysql*") {
        throw "Isolated MySQL identity check failed."
    }
    $script:containerId = $script:containerId.Substring(0, 12)

    $probeSql = ($services | ForEach-Object { "CREATE TABLE buildflow_$_.acct_root_probe (id INT PRIMARY KEY);" }) -join " "
    Assert-Success -Result (Invoke-MySql -Sql $probeSql) -Label "root probe tables"

    # (0) Missing password: script must fail before any change.
    foreach ($svc in $services) { if ($svc -ne "chat") { [Environment]::SetEnvironmentVariable("$($svc.ToUpperInvariant())_DB_PASSWORD", $passwords[$svc]) } }
    $missing = Invoke-AccountScript
    $count = Invoke-MySql -Sql "SELECT COUNT(*) FROM mysql.user WHERE user LIKE 'buildflow\_%';"
    if ($missing.ExitCode -eq 0 -or $missing.StandardError -notmatch "CHAT_DB_PASSWORD" -or $count.StandardOutput.Trim() -ne "0") {
        throw "FAIL [missing password guard]: exit $($missing.ExitCode), accounts=$($count.StandardOutput.Trim())"
    }
    Write-Output "PASS [guard] missing CHAT_DB_PASSWORD rejected, 0 accounts created"

    # (1)-(3) First apply.
    $env:CHAT_DB_PASSWORD = $passwords["chat"]
    $first = Invoke-AccountScript
    if ($first.ExitCode -ne 0) { throw "FAIL [apply]: $($first.StandardError.Trim())" }
    foreach ($secret in $passwords.Values) {
        if ($first.StandardOutput.Contains($secret) -or $first.StandardError.Contains($secret)) { throw "FAIL [apply]: password leaked to output" }
    }
    Write-Output "PASS [apply] script created 7 accounts without echoing passwords"
    Assert-AccountMatrix -Passwords $passwords -Round "first"

    # (4) Re-run with drift: extra privilege must be removed, accounts stay 7.
    Assert-Success -Result (Invoke-MySql -Sql "GRANT SELECT ON buildflow_auth.* TO 'buildflow_site'@'%'; GRANT PROCESS ON *.* TO 'buildflow_tax'@'%';") -Label "inject drift"
    $second = Invoke-AccountScript
    if ($second.ExitCode -ne 0) { throw "FAIL [rerun]: $($second.StandardError.Trim())" }
    Assert-AccountMatrix -Passwords $passwords -Round "rerun"
    Write-Output "PASS [rerun] idempotent, injected drift (cross-schema SELECT, global PROCESS) revoked"

    if ($ValidateSiteService) {
        $env:SITE_DB_PASSWORD = $passwords["site"]
        [void](Invoke-Docker -Arguments ($composeArgs + @("--profile", "site-validation", "up", "-d", "redis", "site-service")) -AllowStandardError)
        $port = ((Invoke-Docker -Arguments ($composeArgs + @("port", "site-service", "8083"))).Trim() -split ":")[-1]
        $deadline = (Get-Date).AddSeconds($SiteHealthTimeoutSeconds)
        $status = $null
        while ((Get-Date) -lt $deadline) {
            try {
                $status = (Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 5).status
                if ($status -eq "UP") { break }
            } catch { $status = $null }
            Start-Sleep -Seconds 3
        }
        if ($status -ne "UP") { throw "FAIL [site-service]: health=$status" }
        $tables = Invoke-MySql -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='buildflow_site' AND table_name <> 'acct_root_probe';"
        Assert-Success -Result $tables -Label "site tables"
        if ([int]$tables.StandardOutput.Trim() -lt 1) { throw "FAIL [site-service]: ddl-auto update created no tables" }
        Write-Output "PASS [site-service] booted as buildflow_site, health UP, Hibernate update created $($tables.StandardOutput.Trim()) tables"
    }
    $completed = $true
} finally {
    if ($started) {
        [void](Invoke-Docker -Arguments ($composeArgs + @("--profile", "site-validation", "down")) -AllowStandardError)
        foreach ($volumeName in @(Get-OutputLines -Text (Invoke-Docker -Arguments @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$projectName")))) {
            $volume = (Invoke-Docker -Arguments @("volume", "inspect", $volumeName)) | ConvertFrom-Json
            if ($volumeName -ceq "${projectName}_account_mysql_data" -and
                (Get-LabelValue -Labels $volume.Labels -Name "com.docker.compose.project") -ceq $projectName -and
                (Get-LabelValue -Labels $volume.Labels -Name "com.buildflow.account-test") -ceq $nonce) {
                [void](Invoke-Docker -Arguments @("volume", "rm", $volumeName))
            } else {
                Write-Warning "Unexpected volume $volumeName left untouched."
            }
        }
        $left = @(Get-OutputLines -Text (Invoke-Docker -Arguments @("ps", "-aq", "--filter", "label=com.docker.compose.project=$projectName"))) +
            @(Get-OutputLines -Text (Invoke-Docker -Arguments @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$projectName")))
        if ($left.Count -gt 0) { Write-Warning "Isolated project $projectName left resources: $($left -join ', ')" }
        elseif ($completed) { Write-Output "PASS [cleanup] isolated project $projectName removed (containers + its own volume)" }
    }
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name]) }
}
