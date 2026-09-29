[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BackupDirectory,
    [switch]$AllowIncomplete
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$schemas = @("buildflow_auth", "buildflow_estimate", "buildflow_site", "buildflow_purchase", "buildflow_tax", "buildflow_notification", "buildflow_chat")
$resolvedDirectory = (Resolve-Path -LiteralPath $BackupDirectory).Path
if (-not $AllowIncomplete -and ((Split-Path -Leaf $resolvedDirectory) -like ".partial-*" -or (Test-Path -LiteralPath (Join-Path $resolvedDirectory "INCOMPLETE")))) {
    throw "Incomplete backup directories cannot be verified: $resolvedDirectory"
}

$manifestPath = Join-Path $resolvedDirectory "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "manifest.json is missing: $manifestPath" }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.formatVersion -ne 1) { throw "Unsupported manifest formatVersion: $($manifest.formatVersion)" }
if ($manifest.containsSensitiveData -ne $true) { throw "Manifest must mark the backup as sensitive data." }
if ($manifest.dump.file -cne "buildflow.sql") { throw "Unexpected dump filename in manifest: $($manifest.dump.file)" }

$requiredSourceFields = @("containerId", "composeProject", "composeWorkingDirectory", "imageReference", "imageId", "volumeName", "volumeDriver")
foreach ($field in $requiredSourceFields) {
    $property = $manifest.source.PSObject.Properties[$field]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "Source identity is missing from manifest: $field"
    }
}
if ([string]$manifest.source.imageReference -cne "mysql:8.0") { throw "Unexpected source image reference in manifest." }

$dumpPath = Join-Path $resolvedDirectory $manifest.dump.file
if (-not (Test-Path -LiteralPath $dumpPath -PathType Leaf)) { throw "Dump file is missing: $dumpPath" }
$dumpFile = Get-Item -LiteralPath $dumpPath
if ($dumpFile.Length -le 0 -or $dumpFile.Length -ne [long]$manifest.dump.bytes) { throw "Dump size does not match manifest." }
$actualHash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -cne ([string]$manifest.dump.sha256).ToLowerInvariant()) { throw "Dump SHA-256 does not match manifest." }

$manifestSchemas = @($manifest.schemas | ForEach-Object { [string]$_.name })
if ($manifestSchemas.Count -ne $schemas.Count -or @($manifestSchemas | Select-Object -Unique).Count -ne $schemas.Count) {
    throw "Manifest must contain exactly seven unique BuildFlow schemas."
}
foreach ($schema in $schemas) {
    if ($schema -notin $manifestSchemas) { throw "Schema is missing from manifest: $schema" }
}

$manifestTables = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
foreach ($schemaEntry in $manifest.schemas) {
    $schemaName = [string]$schemaEntry.name
    Assert-MySqlIdentifier -Value $schemaName
    if ($schemaName -notin $schemas) { throw "Unexpected schema in manifest: $schemaName" }
    $tableNames = @()
    foreach ($table in @($schemaEntry.tables | Where-Object { $null -ne $_ })) {
        $tableName = [string]$table.name
        Assert-MySqlIdentifier -Value $tableName
        if ([long]$table.rowCount -lt 0) { throw "Negative row count in manifest: $schemaName.$tableName" }
        if (-not $manifestTables.Add("$schemaName.$tableName")) { throw "Duplicate table in manifest: $schemaName.$tableName" }
        $tableNames += $tableName
    }
    if ([bool]$schemaEntry.flywayHistoryPresent -ne [bool]($tableNames -contains "flyway_schema_history")) {
        throw "flywayHistoryPresent does not match table inventory: $schemaName"
    }
}

$dumpSchemas = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
$dumpTables = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
$currentSchema = $null
$administratorInsertFound = $false
$completionFooterFound = $false
foreach ($line in [System.IO.File]::ReadLines($dumpPath)) {
    if ($line -match '^CREATE DATABASE .*`(?<schema>buildflow_[a-z0-9_]+)`') {
        [void]$dumpSchemas.Add($Matches.schema)
        continue
    }
    if ($line -match '^USE `(?<schema>buildflow_[a-z0-9_]+)`;') {
        $currentSchema = $Matches.schema
        continue
    }
    if ($null -ne $currentSchema -and $line -match '^CREATE TABLE(?: IF NOT EXISTS)? `(?<table>[a-z0-9_]+)`') {
        if (-not $dumpTables.Add("$currentSchema.$($Matches.table)")) { throw "Duplicate table definition in dump: $currentSchema.$($Matches.table)" }
        continue
    }
    if ($currentSchema -ceq "buildflow_auth" -and $line -match '^INSERT INTO `admin_accounts`') {
        $administratorInsertFound = $true
    }
    if ($line -match '^-- Dump completed on ') { $completionFooterFound = $true }
}

if ($dumpSchemas.Count -ne $schemas.Count) { throw "Dump must contain exactly seven BuildFlow CREATE DATABASE statements." }
foreach ($schema in $schemas) {
    if (-not $dumpSchemas.Contains($schema)) { throw "CREATE DATABASE statement is missing from dump: $schema" }
}
if ($dumpTables.Count -ne $manifestTables.Count) { throw "Dump table count does not match manifest." }
foreach ($tableKey in $manifestTables) {
    if (-not $dumpTables.Contains($tableKey)) { throw "Table definition is missing from dump: $tableKey" }
}
if (-not $manifestTables.Contains("buildflow_auth.admin_accounts") -or -not $administratorInsertFound) {
    throw "Administrator table or row is missing from dump."
}
if (-not $completionFooterFound) { throw "mysqldump completion footer is missing." }

if ([int]$manifest.administrator.count -ne 1) { throw "Manifest must record exactly one administrator." }
if ([string]$manifest.administrator.digestAlgorithm -cne "SHA-256" -or
    [string]::IsNullOrWhiteSpace([string]$manifest.administrator.digest) -or
    [string]$manifest.administrator.digest -cnotmatch '^[0-9a-fA-F]{64}$') {
    throw "Administrator digest is missing or invalid."
}

Write-Host "[OK] Backup manifest, SHA-256, source identity, schemas, tables, administrator row presence, and digest metadata verified."
Write-Host "[OK] $dumpPath"
Write-Warning "Checksum verification detects accidental changes; it is not a cryptographic signature against coordinated dump and manifest tampering."
