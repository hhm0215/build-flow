[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BackupDirectory,
    [Parameter(Mandatory = $true)][string]$RestoredDbRowsPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = [System.IO.Path]::GetFullPath($BackupDirectory)
if ((Split-Path -Leaf $root) -like '.partial-*' -or (Test-Path -LiteralPath (Join-Path $root 'INCOMPLETE'))) {
    throw "Incomplete backup directories cannot be checked."
}
$manifestPath = Join-Path $root "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Backup manifest is missing." }
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if (($manifest.formatVersion -isnot [int] -and $manifest.formatVersion -isnot [long]) -or
    $manifest.formatVersion -ne 2 -or $null -eq $manifest.uploads) {
    throw "Warranty reference verification requires a v2 manifest with uploads."
}
$notificationSchemas = @($manifest.schemas | Where-Object { $_.name -ceq 'buildflow_notification' })
if ($notificationSchemas.Count -ne 1) { throw "Notification schema inventory is missing or duplicated." }
$warrantyTables = @($notificationSchemas[0].tables | Where-Object { $_.name -ceq 'defect_warranties' })
if ($warrantyTables.Count -ne 1) { throw "Warranty table inventory is missing or duplicated." }
$expectedCount = $warrantyTables[0].rowCount
if (($expectedCount -isnot [int] -and $expectedCount -isnot [long]) -or $expectedCount -lt 0) {
    throw "Warranty table row count is invalid."
}

# This proves package integrity only. The caller must export rows from the isolated,
# restored buildflow_notification DB; a supplied JSON file is not proof of provenance.
& (Join-Path $PSScriptRoot "verify-warranty-files.ps1") -BackupDirectory $root -Uploads $manifest.uploads -SqlSource $manifest.source

$rowsPath = [System.IO.Path]::GetFullPath($RestoredDbRowsPath)
if (-not (Test-Path -LiteralPath $rowsPath -PathType Leaf)) { throw "Restored DB row export is missing." }
$rowsText = [System.IO.File]::ReadAllText($rowsPath)
$rows = ConvertFrom-Json -InputObject $rowsText
if ($rowsText.TrimStart() -notmatch '^\[') { throw "Restored DB row export must be a JSON array." }

$mountPath = [string]$manifest.uploads.mountPath
$references = New-Object 'System.Collections.Generic.Dictionary[long,string]'
foreach ($reference in @($manifest.uploads.references)) {
    if ($null -eq $reference -or @($reference.PSObject.Properties).Count -eq 0) { continue }
    if ($reference.warrantyId -isnot [int] -and $reference.warrantyId -isnot [long]) {
        throw "Warranty reference ID must be an integer."
    }
    $id = [long]$reference.warrantyId
    $relativePath = [string]$reference.relativePath
    if ($id -le 0 -or $references.ContainsKey($id)) {
        throw "Invalid or duplicate warranty reference ID."
    }
    $references.Add($id, $relativePath)
}

$dbIds = New-Object 'System.Collections.Generic.HashSet[long]'
$matched = 0
$manual = 0
foreach ($row in @($rows)) {
    if ($null -eq $row -and $rowsText.Trim() -eq '[]') { continue }
    if ($null -eq $row -or $null -eq $row.PSObject.Properties['warrantyId'] -or
        $null -eq $row.PSObject.Properties['filePath']) {
        throw "Restored DB row must include warrantyId and filePath."
    }
    if ($row.warrantyId -isnot [int] -and $row.warrantyId -isnot [long]) {
        throw "Restored warranty ID must be an integer."
    }
    $id = [long]$row.warrantyId
    if ($id -le 0 -or -not $dbIds.Add($id)) { throw "Invalid or duplicate restored warranty ID." }
    if ($null -eq $row.filePath) {
        if ($references.ContainsKey($id)) { throw "Manifest references a warranty without a DB file path." }
        $manual++
        continue
    }
    $filePath = [string]$row.filePath
    if ([string]::IsNullOrWhiteSpace($filePath) -or -not $filePath.StartsWith($mountPath + "/", [System.StringComparison]::Ordinal)) {
        throw "Restored warranty file path is outside the expected mount."
    }
    $relativePath = $filePath.Substring($mountPath.Length + 1)
    if ($relativePath -cnotmatch '^[A-Za-z0-9_-][A-Za-z0-9._-]{0,254}$' -or
        $relativePath -in @('.', '..') -or $relativePath.EndsWith('.')) {
        throw "Unsafe restored warranty file path."
    }
    if (-not $references.ContainsKey($id) -or $references[$id] -cne $relativePath) {
        throw "Restored warranty file reference differs from manifest."
    }
    $matched++
}
if ($matched -ne $references.Count) {
    throw "Manifest warranty references are missing from the restored DB export."
}
if ($dbIds.Count -ne [long]$expectedCount) {
    throw "Restored warranty row count differs from manifest inventory."
}
Write-Host "[OK] Restored warranty DB references match manifest: fileReferences=$matched, noFileRows=$manual. Export provenance and full restore are NOT verified."
