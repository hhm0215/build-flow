[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

function Get-TextSha256 {
    param([Parameter(Mandatory = $true)][string]$Text)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
        return ([BitConverter]::ToString($hasher.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    }
    finally { $hasher.Dispose() }
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-v2-restore-docker-' + [Guid]::NewGuid().ToString('N'))
$package = Join-Path $testRoot ('.partial-synthetic-' + [Guid]::NewGuid().ToString('N'))
$warrantyName = '0f86568e-8ac8-4226-b224-c0f9a7f72f3a.pdf'
$orphanName = '420e6e50-6b73-4aa8-b691-209e870e37be.pdf'
$sourceProject = 'synthetic-source'
$schemas = @('buildflow_auth', 'buildflow_estimate', 'buildflow_site', 'buildflow_purchase', 'buildflow_tax', 'buildflow_notification', 'buildflow_chat')
$passed = $false
try {
    $uploadRoot = Join-Path $package 'uploads\warranties'
    New-Item -ItemType Directory -Path $uploadRoot -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $uploadRoot $warrantyName), 'synthetic-referenced-pdf')
    [IO.File]::WriteAllText((Join-Path $uploadRoot $orphanName), 'synthetic-orphan-pdf')
    [IO.File]::WriteAllText((Join-Path $package 'INCOMPLETE'), 'diagnostic source only')

    $sql = New-Object 'System.Collections.Generic.List[string]'
    foreach ($schema in $schemas) {
        $sql.Add("CREATE DATABASE /*!32312 IF NOT EXISTS*/ ``$schema``;")
        $sql.Add("USE ``$schema``;")
        if ($schema -eq 'buildflow_auth') {
            $sql.Add('CREATE TABLE `admin_accounts` (`id` BIGINT NOT NULL PRIMARY KEY, `login_id` VARCHAR(50) NOT NULL UNIQUE, `password` VARCHAR(255) NOT NULL, `name` VARCHAR(50) NOT NULL, `created_at` DATETIME(6) NOT NULL, CONSTRAINT chk_single_admin_id CHECK (`id` = 1));')
            $sql.Add("INSERT INTO ``admin_accounts`` VALUES (1, 'qaadmin', 'synthetic-hash', 'QA', '2026-10-16 00:00:00.000000');")
        }
        if ($schema -eq 'buildflow_notification') {
            $sql.Add('CREATE TABLE `defect_warranties` (`id` BIGINT NOT NULL PRIMARY KEY, `file_path` VARCHAR(1024) NULL);')
            $sql.Add("INSERT INTO ``defect_warranties`` VALUES (1, '/app/uploads/warranties/$warrantyName'), (2, NULL);")
        }
    }
    $sql.Add('-- Dump completed on 2026-10-16 00:00:00')
    $dumpPath = Join-Path $package 'buildflow.sql'
    [IO.File]::WriteAllText($dumpPath, ($sql -join "`n") + "`n", (New-Object Text.UTF8Encoding($false)))
    $files = @(@($warrantyName, $orphanName) | ForEach-Object {
        $path = Join-Path $uploadRoot $_
        [ordered]@{ relativePath = $_; bytes = (Get-Item -LiteralPath $path).Length; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    $inner = Get-TextSha256 -Text '1|qaadmin|synthetic-hash|QA|2026-10-16T00:00:00.000000'
    $adminDigest = Get-TextSha256 -Text $inner
    $manifest = [ordered]@{
        formatVersion = 2
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        containsSensitiveData = $true
        mysqlServerVersion = 'synthetic'
        source = [ordered]@{
            containerId = 'synthetic-source-container'
            composeProject = $sourceProject
            composeWorkingDirectory = $testRoot
            imageReference = 'mysql:8.0'
            imageId = 'sha256:' + ('a' * 64)
            volumeName = 'synthetic_source_mysql_data'
            volumeDriver = 'local'
        }
        schemas = @($schemas | ForEach-Object {
            $tables = if ($_ -eq 'buildflow_auth') { @([ordered]@{ name = 'admin_accounts'; rowCount = 1L }) }
                elseif ($_ -eq 'buildflow_notification') { @([ordered]@{ name = 'defect_warranties'; rowCount = 2L }) }
                else { @() }
            [ordered]@{ name = $_; tables = $tables; flywayHistoryPresent = $false }
        })
        dump = [ordered]@{ file = 'buildflow.sql'; bytes = (Get-Item -LiteralPath $dumpPath).Length; sha256 = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant() }
        administrator = [ordered]@{ count = 1; digestAlgorithm = 'SHA-256'; digest = $adminDigest }
        security = [ordered]@{ plaintext = $true; broadReadAclWarning = $false }
        uploads = [ordered]@{
            directory = 'uploads/warranties'
            mountPath = '/app/uploads/warranties'
            source = [ordered]@{ composeProject = $sourceProject; volumeName = 'synthetic_source_warranty_uploads'; volumeDriver = 'local' }
            files = $files
            references = @([ordered]@{ warrantyId = 1L; relativePath = $warrantyName })
            referenceCount = 1
            unreferencedFileCount = 1
        }
        recoveryStatus = 'source-snapshot-only; isolated DB+volume restore pending'
    }
    Write-JsonUtf8 -Path (Join-Path $package 'manifest.json') -Value $manifest
    $defaultRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'restore-test.ps1') -BackupDirectory $package 2>&1 | Out-Null
    }
    catch {
        $defaultRejected = $_.Exception.Message -like '*V2 diagnostic restore requires -TestIncompleteV2*'
    }
    if (-not $defaultRejected) { throw 'Incomplete v2 package was not rejected by the default restore path.' }
    & (Join-Path $PSScriptRoot 'restore-test.ps1') -BackupDirectory $package -TestIncompleteV2
    $v1Package = Join-Path $testRoot 'complete-synthetic-v1'
    New-Item -ItemType Directory -Path $v1Package | Out-Null
    [IO.File]::Copy($dumpPath, (Join-Path $v1Package 'buildflow.sql'))
    $v1Manifest = Get-Content -LiteralPath (Join-Path $package 'manifest.json') -Raw | ConvertFrom-Json
    $v1Manifest.formatVersion = 1
    [void]$v1Manifest.PSObject.Properties.Remove('uploads')
    [void]$v1Manifest.PSObject.Properties.Remove('recoveryStatus')
    Write-JsonUtf8 -Path (Join-Path $v1Package 'manifest.json') -Value $v1Manifest
    & (Join-Path $PSScriptRoot 'restore-test.ps1') -BackupDirectory $v1Package
    $passed = $true
    Write-Host '[OK] Synthetic v2 SQL+file and v1 SQL-only isolated Docker restores passed; admin credential login and external recovery were not tested.'
}
finally {
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Synthetic test path escaped the temporary directory.' }
    if ($passed -and (Test-Path -LiteralPath $resolved)) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    if (-not $passed) { Write-Warning "Synthetic source package retained for inspection: $resolved" }
}
