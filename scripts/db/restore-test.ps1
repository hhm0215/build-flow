[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,
    [switch]$ValidateLogin
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$composeFile = Join-Path $PSScriptRoot "restore-test.compose.yml"
$verifyScript = Join-Path $PSScriptRoot "verify-backup.ps1"
$mysqlShell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names --raw --default-character-set=utf8mb4 --execute="$1"'
$projectName = "buildflowrestore$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
$nonce = [Guid]::NewGuid().ToString("N")
$cleanupAuthorized = $false
$projectStarted = $false
$containerId = $null
$volumeName = $null
$previousEnvironment = @{}
$environmentNames = @("RESTORE_TEST_DB_PASSWORD", "RESTORE_TEST_JWT_SECRET", "RESTORE_TEST_NONCE")

function New-RandomHex {
    param([Parameter(Mandatory = $true)][int]$ByteCount)

    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) }
    finally { $generator.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString("x2") })
}

function Invoke-DockerText {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowStandardError
    )

    return (Invoke-NativeProcess -FileName "docker" -Arguments $Arguments -WorkingDirectory $repoRoot -RejectStandardError:(-not $AllowStandardError.IsPresent)).StandardOutput
}

function Get-LabelValue {
    param([Parameter(Mandatory = $true)][object]$Labels, [Parameter(Mandatory = $true)][string]$Name)
    $property = $Labels.PSObject.Properties[$Name]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "Required Docker label is missing: $Name"
    }
    return [string]$property.Value
}

function Invoke-RestoreQuery {
    param([Parameter(Mandatory = $true)][string]$Query)
    $arguments = $composeArgs + @("exec", "-T", "mysql", "sh", "-c", $mysqlShell, "buildflow-restore-query", $Query)
    return @(Get-OutputLines -Text (Invoke-DockerText -Arguments $arguments))
}

function Import-DatabaseDump {
    param([Parameter(Mandatory = $true)][string]$DumpPath)

    $arguments = $composeArgs + @(
        "exec", "-T", "mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --binary-mode --default-character-set=utf8mb4'
    )
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-NativeProcessStartInfo -FileName "docker" -Arguments $arguments -WorkingDirectory $repoRoot
    $process.StartInfo.RedirectStandardInput = $true
    $fileStream = $null
    try {
        if (-not $process.Start()) { throw "Could not start isolated MySQL restore process." }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $fileStream = [System.IO.File]::OpenRead($DumpPath)
        $copyTask = $fileStream.CopyToAsync($process.StandardInput.BaseStream)
        [void]$copyTask.GetAwaiter().GetResult()
        $process.StandardInput.Close()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "MySQL restore failed with exit code $($process.ExitCode): $($stderr.Trim())" }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) { throw "MySQL restore wrote unexpected stderr: $($stderr.Trim())" }
        if (-not [string]::IsNullOrWhiteSpace($stdout)) { throw "MySQL restore wrote unexpected stdout." }
    }
    finally {
        if ($null -ne $fileStream) { $fileStream.Dispose() }
        $process.Dispose()
    }
}

function Assert-RestoreInventory {
    param([Parameter(Mandatory = $true)][object]$Manifest)

    $actualSchemas = @(Invoke-RestoreQuery -Query "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE LEFT(SCHEMA_NAME, 10) = 'buildflow_' ORDER BY SCHEMA_NAME;")
    $expectedSchemas = @($Manifest.schemas | ForEach-Object { [string]$_.name } | Sort-Object)
    if (($actualSchemas -join "`n") -cne ($expectedSchemas -join "`n")) {
        throw "Restored BuildFlow schema list does not match the manifest."
    }

    foreach ($schema in $Manifest.schemas) {
        Assert-MySqlIdentifier -Value ([string]$schema.name)
        $actualTables = @(Invoke-RestoreQuery -Query "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '$($schema.name)' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME;")
        $expectedTables = @($schema.tables | ForEach-Object { [string]$_.name } | Sort-Object)
        if (($actualTables -join "`n") -cne ($expectedTables -join "`n")) {
            throw "Restored table list does not match the manifest for $($schema.name)."
        }

        foreach ($table in $schema.tables) {
            Assert-MySqlIdentifier -Value ([string]$table.name)
            $rowCount = [long]((Invoke-RestoreQuery -Query "SELECT COUNT(*) FROM ``$($schema.name)``.``$($table.name)``;") | Select-Object -First 1)
            if ($rowCount -ne [long]$table.rowCount) {
                throw "Row count mismatch for $($schema.name).$($table.name): expected $($table.rowCount), actual $rowCount"
            }
            $checkResult = @((Invoke-RestoreQuery -Query "CHECK TABLE ``$($schema.name)``.``$($table.name)``;") | Select-Object -First 1)
            $parts = $checkResult -split "`t"
            if ($parts.Count -lt 4 -or $parts[1] -cne "check" -or $parts[2] -cne "status" -or $parts[3] -cne "OK") {
                throw "CHECK TABLE failed for $($schema.name).$($table.name)."
            }
        }

        $flywayPresent = [bool]($actualTables -contains "flyway_schema_history")
        if ($flywayPresent -ne [bool]$schema.flywayHistoryPresent) {
            throw "Flyway history presence mismatch for $($schema.name)."
        }
    }

    $fingerprint = @((Invoke-RestoreQuery -Query "SELECT COUNT(*), COALESCE(SHA2(GROUP_CONCAT(SHA2(CONCAT_WS('|', id, login_id, password, name, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f')), 256) ORDER BY id SEPARATOR '|'), 256), '') FROM buildflow_auth.admin_accounts;") | Select-Object -First 1)
    $fingerprintParts = $fingerprint -split "`t", 2
    if ($fingerprintParts.Count -ne 2 -or [int]$fingerprintParts[0] -ne [int]$Manifest.administrator.count -or
        $fingerprintParts[1].Trim().ToLowerInvariant() -cne ([string]$Manifest.administrator.digest).ToLowerInvariant()) {
        throw "Restored administrator count or digest does not match the manifest."
    }
}

function Wait-ForContainerHealth {
    param([Parameter(Mandatory = $true)][string]$Id, [int]$TimeoutSeconds = 90)

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $state = (Invoke-DockerText -Arguments @("inspect", "--format", "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}", $Id)).Trim()
        if ($state -eq "running|healthy") { return }
        if ($state.StartsWith("exited|", [System.StringComparison]::Ordinal)) { throw "Container exited before becoming healthy: $Id" }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Container did not become healthy within $TimeoutSeconds seconds: $Id"
}

function Start-AuthValidation {
    param([switch]$TestLogin)

    [void](Invoke-DockerText -Arguments ($composeArgs + @("--profile", "auth-validation", "up", "-d", "redis", "auth-service")) -AllowStandardError)
    $authId = (Invoke-DockerText -Arguments ($composeArgs + @("ps", "-q", "auth-service"))).Trim()
    if ([string]::IsNullOrWhiteSpace($authId)) { throw "Could not resolve the isolated auth-service container." }
    $authInspect = @((Invoke-DockerText -Arguments @("inspect", $authId) | ConvertFrom-Json)) | Select-Object -First 1
    if ((Get-LabelValue -Labels $authInspect.Config.Labels -Name "com.docker.compose.project") -cne $projectName -or
        (Get-LabelValue -Labels $authInspect.Config.Labels -Name "com.buildflow.restore-test") -cne $nonce) {
        throw "Isolated auth-service identity validation failed."
    }

    $portOutput = (Invoke-DockerText -Arguments ($composeArgs + @("port", "auth-service", "8081"))).Trim()
    if ($portOutput -cnotmatch ':(?<port>[0-9]+)$') { throw "Could not resolve the isolated auth-service host port." }
    $baseUrl = "http://127.0.0.1:$($Matches.port)"
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    $health = $null
    do {
        try {
            $health = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 3
            if ($health.status -ceq "UP") { break }
        }
        catch {
            $status = (Invoke-DockerText -Arguments @("inspect", "--format", "{{.State.Status}}", $authId)).Trim()
            if ($status -eq "exited") { throw "Isolated auth-service exited before validation completed." }
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($null -eq $health -or $health.status -cne "UP") { throw "Isolated auth-service health validation timed out." }
    Write-Host "[OK] Restored auth schema passed Hibernate ddl-auto=validate startup."

    if (-not $TestLogin) {
        Write-Warning "Administrator login was not tested. Re-run with -ValidateLogin for the final Phase B credential check."
        return
    }

    $loginId = Read-Host "Admin login ID for isolated restore"
    $securePassword = Read-Host "Admin password for isolated restore" -AsSecureString
    $passwordPointer = [IntPtr]::Zero
    $plainPassword = $null
    $loginBody = $null
    $accessToken = $null
    try {
        if ($loginId -cnotmatch '^[A-Za-z0-9._-]{3,50}$') { throw "Admin login ID format is invalid." }
        $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
        $loginBody = [Text.Encoding]::UTF8.GetBytes((@{ loginId = $loginId; password = $plainPassword } | ConvertTo-Json -Compress))
        $login = Invoke-RestMethod -Uri "$baseUrl/api/v1/auth/login" -Method Post -ContentType "application/json; charset=utf-8" -Body $loginBody -TimeoutSec 15
        if (-not $login.success -or [string]::IsNullOrWhiteSpace([string]$login.data.accessToken)) {
            throw "Isolated administrator login did not return an access token."
        }
        $accessToken = [string]$login.data.accessToken
        Invoke-RestMethod -Uri "$baseUrl/api/v1/auth/logout" -Method Post -Headers @{ Authorization = "Bearer $accessToken" } -TimeoutSec 15 | Out-Null
        Write-Host "[OK] Restored administrator login and logout passed."
    }
    finally {
        if ($null -ne $loginBody) { [Array]::Clear($loginBody, 0, $loginBody.Length) }
        if ($passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
        $plainPassword = $null
        $accessToken = $null
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker is required." }
$backupPath = [System.IO.Path]::GetFullPath($BackupDirectory)
if (-not (Test-Path -LiteralPath $backupPath -PathType Container)) { throw "Backup directory not found: $backupPath" }
& $verifyScript -BackupDirectory $backupPath
$manifest = Get-Content -Raw -LiteralPath (Join-Path $backupPath "manifest.json") | ConvertFrom-Json
$dumpPath = Join-Path $backupPath ([string]$manifest.dump.file)

$composeArgs = @("compose", "--project-name", $projectName, "-f", $composeFile)
foreach ($name in $environmentNames) {
    $existing = Get-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    $previousEnvironment[$name] = if ($null -eq $existing) { $null } else { [string]$existing.Value }
}
$env:RESTORE_TEST_DB_PASSWORD = New-RandomHex -ByteCount 24
$env:RESTORE_TEST_JWT_SECRET = New-RandomHex -ByteCount 64
$env:RESTORE_TEST_NONCE = $nonce

Push-Location $repoRoot
try {
    $existingResources = @(Get-OutputLines -Text (Invoke-DockerText -Arguments ($composeArgs + @("ps", "-aq"))))
    $existingVolumes = @(Get-OutputLines -Text (Invoke-DockerText -Arguments @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$projectName")))
    $existingNetworks = @(Get-OutputLines -Text (Invoke-DockerText -Arguments @("network", "ls", "-q", "--filter", "label=com.docker.compose.project=$projectName")))
    if ($existingResources.Count -ne 0 -or $existingVolumes.Count -ne 0 -or $existingNetworks.Count -ne 0) {
        throw "Random restore-test project name unexpectedly already has Docker resources."
    }

    [void](Invoke-DockerText -Arguments ($composeArgs + @("up", "-d", "mysql")) -AllowStandardError)
    $projectStarted = $true
    $containerId = (Invoke-DockerText -Arguments ($composeArgs + @("ps", "-q", "mysql"))).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) { throw "Could not resolve the isolated MySQL container." }
    $container = @((Invoke-DockerText -Arguments @("inspect", $containerId) | ConvertFrom-Json)) | Select-Object -First 1
    if ((Get-LabelValue -Labels $container.Config.Labels -Name "com.docker.compose.project") -cne $projectName -or
        (Get-LabelValue -Labels $container.Config.Labels -Name "com.docker.compose.service") -cne "mysql" -or
        (Get-LabelValue -Labels $container.Config.Labels -Name "com.buildflow.restore-test") -cne $nonce) {
        throw "Isolated MySQL container identity validation failed."
    }
    if ([string]$container.Name -ceq "/buildflow-mysql" -or [string]$container.Id -ceq [string]$manifest.source.containerId) {
        throw "Restore test resolved to the live or backup source container."
    }
    $dataMounts = @($container.Mounts | Where-Object { $_.Destination -eq "/var/lib/mysql" })
    if ($dataMounts.Count -ne 1 -or $dataMounts[0].Type -cne "volume") { throw "Isolated MySQL must have exactly one named data volume." }
    $volumeName = [string]$dataMounts[0].Name
    if ([string]::IsNullOrWhiteSpace($volumeName) -or $volumeName -ceq [string]$manifest.source.volumeName) {
        throw "Restore test resolved to the backup source volume."
    }

    $liveContainerIds = @(Get-OutputLines -Text (Invoke-DockerText -Arguments @(
        "ps", "-aq",
        "--filter", "label=com.docker.compose.project=$($manifest.source.composeProject)",
        "--filter", "label=com.docker.compose.service=mysql"
    )))
    if ($liveContainerIds.Count -gt 1) { throw "More than one live source-project MySQL container was found." }
    if ($liveContainerIds.Count -eq 1) {
        $liveContainer = @((Invoke-DockerText -Arguments @("inspect", $liveContainerIds[0]) | ConvertFrom-Json)) | Select-Object -First 1
        $liveMounts = @($liveContainer.Mounts | Where-Object { $_.Destination -eq "/var/lib/mysql" })
        if ([string]$liveContainer.Id -ceq [string]$container.Id -or
            ($liveMounts.Count -eq 1 -and [string]$liveMounts[0].Name -ceq $volumeName)) {
            throw "Restore test resolved to the currently running BuildFlow MySQL container or volume."
        }
    }

    $volume = @((Invoke-DockerText -Arguments @("volume", "inspect", $volumeName) | ConvertFrom-Json)) | Select-Object -First 1
    if ((Get-LabelValue -Labels $volume.Labels -Name "com.docker.compose.project") -cne $projectName -or
        (Get-LabelValue -Labels $volume.Labels -Name "com.docker.compose.volume") -cne "restore_mysql_data" -or
        (Get-LabelValue -Labels $volume.Labels -Name "com.buildflow.restore-test") -cne $nonce) {
        throw "Isolated restore volume identity validation failed."
    }
    $cleanupAuthorized = $true

    Wait-ForContainerHealth -Id $containerId
    Import-DatabaseDump -DumpPath $dumpPath
    Assert-RestoreInventory -Manifest $manifest
    Write-Host "[OK] Restore manifest, exact row counts, administrator digest, and CHECK TABLE results match."
    Start-AuthValidation -TestLogin:$ValidateLogin
    Assert-RestoreInventory -Manifest $manifest
    Write-Host "[OK] Auth validation left the restored schema and data unchanged."
}
finally {
    $cleanupError = $null
    if ($cleanupAuthorized) {
        try {
            [void](Invoke-DockerText -Arguments ($composeArgs + @("--profile", "auth-validation", "down", "--volumes", "--remove-orphans")) -AllowStandardError)
            $remainingContainers = @(Get-OutputLines -Text (Invoke-DockerText -Arguments @("ps", "-aq", "--filter", "label=com.docker.compose.project=$projectName")))
            $remainingVolumes = @(Get-OutputLines -Text (Invoke-DockerText -Arguments @("volume", "ls", "-q", "--filter", "label=com.docker.compose.project=$projectName")))
            $remainingNetworks = @(Get-OutputLines -Text (Invoke-DockerText -Arguments @("network", "ls", "-q", "--filter", "label=com.docker.compose.project=$projectName")))
            if ($remainingContainers.Count -ne 0 -or $remainingVolumes.Count -ne 0 -or $remainingNetworks.Count -ne 0) {
                throw "Isolated restore cleanup left project resources behind: $projectName"
            }
            Write-Host "[OK] Isolated restore project and temporary volume removed: $projectName"
        }
        catch {
            $cleanupError = $_.Exception
        }
    }
    elseif ($projectStarted) {
        Write-Warning "Restore identity validation did not complete. Project $projectName was preserved for manual inspection."
    }

    foreach ($name in $environmentNames) {
        if ($null -eq $previousEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath "Env:$name" -Value $previousEnvironment[$name] }
    }
    Pop-Location
    if ($null -ne $cleanupError) {
        throw "Automatic cleanup failed for isolated project $projectName. Inspect its labels before manual removal: $($cleanupError.Message)"
    }
}
