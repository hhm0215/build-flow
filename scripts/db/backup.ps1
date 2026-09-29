[CmdletBinding()]
param([string]$OutputRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($OutputRoot)) { $OutputRoot = Join-Path $repoRoot "backups\db" }
$schemas = @("buildflow_auth", "buildflow_estimate", "buildflow_site", "buildflow_purchase", "buildflow_tax", "buildflow_notification", "buildflow_chat")
$composeArgs = @("compose", "-f", (Join-Path $repoRoot "docker-compose.yml"), "-f", (Join-Path $repoRoot "docker-compose.app.yml"))
$mysqlShell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names --raw --execute="$1"'
$writeCapableServices = @("auth-service", "estimate-service", "site-service", "purchase-service", "tax-service", "notification-service", "chat-service")
$backupDirectory = $null

function Invoke-DockerText {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return (Invoke-NativeProcess -FileName "docker" -Arguments $Arguments -WorkingDirectory $repoRoot -RejectStandardError).StandardOutput
}

function Invoke-DockerLines {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return @(Get-OutputLines -Text (Invoke-DockerText -Arguments $Arguments))
}

function Invoke-MySqlQuery {
    param([Parameter(Mandatory = $true)][string]$Query)
    $arguments = $composeArgs + @("exec", "-T", "mysql", "sh", "-c", $mysqlShell, "buildflow-query", $Query)
    return @(Invoke-DockerLines -Arguments $arguments)
}

function Get-DatabaseInventory {
    $inventory = @()
    foreach ($schema in $schemas) {
        Assert-MySqlIdentifier -Value $schema
        $tables = @(Invoke-MySqlQuery -Query "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '$schema' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME;")
        $tableInventory = @()
        foreach ($tableValue in $tables) {
            $table = $tableValue.Trim()
            Assert-MySqlIdentifier -Value $table
            $rowCount = [long]((Invoke-MySqlQuery -Query "SELECT COUNT(*) FROM ``$schema``.``$table``;") | Select-Object -First 1)
            $tableInventory += [ordered]@{ name = $table; rowCount = $rowCount }
        }
        $inventory += [ordered]@{
            name = $schema
            tables = $tableInventory
            flywayHistoryPresent = [bool]($tables -contains "flyway_schema_history")
        }
    }
    return @($inventory)
}

function Get-AdministratorFingerprint {
    $result = @(Invoke-MySqlQuery -Query "SELECT COUNT(*), COALESCE(SHA2(GROUP_CONCAT(SHA2(CONCAT_WS('|', id, login_id, password, name, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%f')), 256) ORDER BY id SEPARATOR '|'), 256), '') FROM buildflow_auth.admin_accounts;") | Select-Object -First 1
    $parts = $result -split "`t", 2
    if ($parts.Count -ne 2 -or [int]$parts[0] -ne 1 -or $parts[1] -cnotmatch '^[0-9a-fA-F]{64}$') {
        throw "Expected exactly one administrator account before accepting the backup."
    }
    return [ordered]@{ count = 1; digestAlgorithm = "SHA-256"; digest = $parts[1].Trim().ToLowerInvariant() }
}

function Assert-NoActiveBuildFlowClients {
    $query = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.PROCESSLIST WHERE ID <> CONNECTION_ID() AND DB IS NOT NULL AND LEFT(DB, 10) = 'buildflow_';"
    $activeCount = [int]((Invoke-MySqlQuery -Query $query) | Select-Object -First 1)
    if ($activeCount -ne 0) {
        throw "Detected $activeCount active BuildFlow database connection(s). Stop local bootRun and all external writers before backup."
    }
}

function Get-LabelValue {
    param([Parameter(Mandatory = $true)][object]$Labels, [Parameter(Mandatory = $true)][string]$Name)
    $property = $Labels.PSObject.Properties[$Name]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "Required Docker label is missing: $Name"
    }
    return [string]$property.Value
}

function Get-SourceIdentity {
    param([Parameter(Mandatory = $true)][string]$ContainerId)

    $container = @((Invoke-DockerText -Arguments @("inspect", $ContainerId) | ConvertFrom-Json)) | Select-Object -First 1
    if ((Get-LabelValue -Labels $container.Config.Labels -Name "com.docker.compose.service") -cne "mysql") {
        throw "Resolved container is not the Compose mysql service."
    }
    $project = Get-LabelValue -Labels $container.Config.Labels -Name "com.docker.compose.project"
    $workingDirectory = Get-LabelValue -Labels $container.Config.Labels -Name "com.docker.compose.project.working_dir"
    $expectedWorkingDirectory = [System.IO.Path]::GetFullPath($repoRoot).TrimEnd('\', '/')
    $actualWorkingDirectory = [System.IO.Path]::GetFullPath($workingDirectory).TrimEnd('\', '/')
    if (-not $actualWorkingDirectory.Equals($expectedWorkingDirectory, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "MySQL Compose working directory does not match this repository: $workingDirectory"
    }
    if ([string]$container.Config.Image -cne "mysql:8.0") {
        throw "Unexpected MySQL image reference: $($container.Config.Image)"
    }

    $dataMounts = @($container.Mounts | Where-Object { $_.Destination -eq "/var/lib/mysql" })
    if ($dataMounts.Count -ne 1 -or $dataMounts[0].Type -cne "volume" -or [string]::IsNullOrWhiteSpace([string]$dataMounts[0].Name)) {
        throw "MySQL /var/lib/mysql must use exactly one named Docker volume."
    }
    $volumeName = [string]$dataMounts[0].Name
    $volume = @((Invoke-DockerText -Arguments @("volume", "inspect", $volumeName) | ConvertFrom-Json)) | Select-Object -First 1
    if ((Get-LabelValue -Labels $volume.Labels -Name "com.docker.compose.project") -cne $project -or
        (Get-LabelValue -Labels $volume.Labels -Name "com.docker.compose.volume") -cne "mysql_data") {
        throw "MySQL data volume labels do not match this Compose project and mysql_data."
    }
    return [ordered]@{
        containerId = [string]$container.Id
        composeProject = $project
        composeWorkingDirectory = $actualWorkingDirectory
        imageReference = [string]$container.Config.Image
        imageId = [string]$container.Image
        volumeName = $volumeName
        volumeDriver = [string]$volume.Driver
    }
}

function Test-BroadReadAcl {
    param([Parameter(Mandatory = $true)][string]$Path)

    try {
        $broadIdentities = @("Everyone", "BUILTIN\Users", "NT AUTHORITY\Authenticated Users")
        $readRights = [System.Security.AccessControl.FileSystemRights]::Read -bor [System.Security.AccessControl.FileSystemRights]::ReadAndExecute -bor [System.Security.AccessControl.FileSystemRights]::ReadData
        foreach ($rule in (Get-Acl -LiteralPath $Path).Access) {
            if ($rule.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Allow -and
                [string]$rule.IdentityReference -in $broadIdentities -and (($rule.FileSystemRights -band $readRights) -ne 0)) {
                return $true
            }
        }
    }
    catch {
        Write-Warning "Could not inspect backup directory ACL: $($_.Exception.Message)"
        return $true
    }
    return $false
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker is required. Install and start Docker Desktop." }

Push-Location $repoRoot
try {
    Invoke-DockerText -Arguments @("info", "--format", "{{.ServerVersion}}") | Out-Null
    $containerId = (Invoke-DockerLines -Arguments ($composeArgs + @("ps", "-q", "mysql")) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        throw "The BuildFlow MySQL container is not running. Start it with docker compose up -d mysql."
    }
    $sourceIdentity = Get-SourceIdentity -ContainerId $containerId

    $runningServices = @(Invoke-DockerLines -Arguments ($composeArgs + @("ps", "--services", "--status", "running")))
    $runningWriters = @($runningServices | Where-Object { $_ -in $writeCapableServices })
    if ($runningWriters.Count -gt 0) {
        throw "Database-writing services must be stopped for a consistent backup. Running=[$($runningWriters -join ', ')]. Use docker compose down, then start only mysql before retrying."
    }

    $existingSchemas = @(Invoke-MySqlQuery -Query "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE LEFT(SCHEMA_NAME, 10) = 'buildflow_' ORDER BY SCHEMA_NAME;")
    $unexpectedSchemas = @($existingSchemas | Where-Object { $_ -notin $schemas })
    $missingSchemas = @($schemas | Where-Object { $_ -notin $existingSchemas })
    if ($unexpectedSchemas.Count -gt 0 -or $missingSchemas.Count -gt 0) {
        throw "BuildFlow schema allowlist mismatch. Missing=[$($missingSchemas -join ', ')], unexpected=[$($unexpectedSchemas -join ', ')]."
    }

    Assert-NoActiveBuildFlowClients
    $inventoryBefore = @(Get-DatabaseInventory)
    $administratorBefore = Get-AdministratorFingerprint

    $outputRootFull = [System.IO.Path]::GetFullPath($OutputRoot)
    if (-not (Test-Path -LiteralPath $outputRootFull)) { New-Item -ItemType Directory -Path $outputRootFull | Out-Null }
    $timestamp = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
    $finalDirectory = Join-Path $outputRootFull $timestamp
    $partialDirectory = Join-Path $outputRootFull (".partial-$timestamp-" + [Guid]::NewGuid().ToString("N"))
    if (Test-Path -LiteralPath $finalDirectory) { throw "Backup directory already exists: $finalDirectory" }
    New-Item -ItemType Directory -Path $partialDirectory | Out-Null
    $backupDirectory = $partialDirectory
    $incompleteMarker = Join-Path $partialDirectory "INCOMPLETE"
    [System.IO.File]::WriteAllText($incompleteMarker, "Backup has not completed verification.`n")
    $broadReadAcl = Test-BroadReadAcl -Path $partialDirectory
    if ($broadReadAcl) {
        Write-Warning "Backup directory may be readable by a broad Windows group. Store the completed backup on an encrypted, access-controlled device."
    }

    $dumpPath = Join-Path $partialDirectory "buildflow.sql"
    $dumpShell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --quick --routines --triggers --events --hex-blob --set-gtid-purged=OFF --no-tablespaces --default-character-set=utf8mb4 --databases "$@"'
    $dumpArguments = $composeArgs + @("exec", "-T", "mysql", "sh", "-c", $dumpShell, "buildflow-dump") + $schemas
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-NativeProcessStartInfo -FileName "docker" -Arguments $dumpArguments -WorkingDirectory $repoRoot
    $fileStream = $null
    try {
        if (-not $process.Start()) { throw "Could not start mysqldump." }
        $fileStream = [System.IO.File]::Create($dumpPath)
        $copyTask = $process.StandardOutput.BaseStream.CopyToAsync($fileStream)
        $errorTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [void]$copyTask.GetAwaiter().GetResult()
        $stderr = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "mysqldump failed with exit code $($process.ExitCode): $($stderr.Trim())" }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) { throw "mysqldump wrote unexpected stderr: $($stderr.Trim())" }
    }
    finally {
        if ($null -ne $fileStream) { $fileStream.Dispose() }
        $process.Dispose()
    }

    Assert-NoActiveBuildFlowClients
    $inventoryAfter = @(Get-DatabaseInventory)
    $administratorAfter = Get-AdministratorFingerprint
    if (($inventoryBefore | ConvertTo-Json -Depth 8 -Compress) -cne ($inventoryAfter | ConvertTo-Json -Depth 8 -Compress) -or
        ($administratorBefore | ConvertTo-Json -Compress) -cne ($administratorAfter | ConvertTo-Json -Compress)) {
        throw "Database inventory changed while the backup was running. The dump is not accepted."
    }

    $hash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $serverVersion = (Invoke-MySqlQuery -Query "SELECT VERSION();" | Select-Object -First 1).Trim()
    $manifest = [ordered]@{
        formatVersion = 1
        createdAtUtc = [DateTime]::UtcNow.ToString("o")
        containsSensitiveData = $true
        mysqlServerVersion = $serverVersion
        source = $sourceIdentity
        schemas = $inventoryBefore
        dump = [ordered]@{ file = "buildflow.sql"; bytes = (Get-Item -LiteralPath $dumpPath).Length; sha256 = $hash }
        administrator = $administratorBefore
        security = [ordered]@{ plaintext = $true; broadReadAclWarning = $broadReadAcl }
    }
    Write-JsonUtf8 -Path (Join-Path $partialDirectory "manifest.json") -Value $manifest

    & (Join-Path $PSScriptRoot "verify-backup.ps1") -BackupDirectory $partialDirectory -AllowIncomplete
    Remove-Item -LiteralPath $incompleteMarker
    Move-Item -LiteralPath $partialDirectory -Destination $finalDirectory
    $backupDirectory = $finalDirectory

    Write-Host "[OK] Backup created and verified: $finalDirectory"
    Write-Host "[OK] SHA-256: $hash"
    Write-Warning "The SQL dump contains real business data and a password hash. Move it to encrypted, access-controlled storage."
}
catch {
    if ($null -ne $backupDirectory -and (Test-Path -LiteralPath $backupDirectory)) {
        Write-Warning "Incomplete backup retained for inspection: $backupDirectory"
    }
    throw
}
finally {
    Pop-Location
}
