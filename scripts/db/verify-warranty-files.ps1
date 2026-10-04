[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BackupDirectory,
    [Parameter(Mandatory = $true)][object]$Uploads,
    [Parameter(Mandatory = $true)][object]$SqlSource
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = [System.IO.Path]::GetFullPath($BackupDirectory)
if ([string]$Uploads.mountPath -cne "/app/uploads/warranties") { throw "Unexpected warranty mount path." }
if ([string]$Uploads.directory -cne "uploads/warranties") { throw "Unexpected warranty package directory." }
$source = $Uploads.source
if ($null -eq $source) { throw "Warranty source identity is missing." }
foreach ($field in @("composeProject", "volumeName", "volumeDriver")) {
    if ($null -eq $source.PSObject.Properties[$field] -or [string]::IsNullOrWhiteSpace([string]$source.$field)) {
        throw "Warranty source identity is missing: $field"
    }
}
if ([string]$source.volumeDriver -cne "local") { throw "Unexpected warranty volume driver." }
if ([string]$source.composeProject -cne [string]$SqlSource.composeProject -or
    [string]$source.volumeName -ceq [string]$SqlSource.volumeName) {
    throw "Warranty source does not match the SQL Compose project or points to the SQL volume."
}

$uploadRoot = Join-Path $root "uploads\warranties"
if (-not (Test-Path -LiteralPath $uploadRoot -PathType Container)) { throw "Warranty package directory is missing." }
foreach ($directory in @((Join-Path $root "uploads"), $uploadRoot)) {
    $directoryItem = Get-Item -LiteralPath $directory -Force
    if (($directoryItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Warranty package directory must not be a link."
    }
}
$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
foreach ($entry in @($Uploads.files)) {
    if ($null -eq $entry -or @($entry.PSObject.Properties).Count -eq 0) { continue }
    $relativePath = [string]$entry.relativePath
    if ($relativePath -cnotmatch '^[A-Za-z0-9_-][A-Za-z0-9._-]{0,254}$' -or
        $relativePath -in @(".", "..") -or $relativePath.EndsWith(".")) {
        throw "Unsafe warranty relative path in manifest."
    }
    if (-not $seen.Add($relativePath)) { throw "Duplicate warranty file in manifest." }
    $filePath = Join-Path $uploadRoot $relativePath
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) { throw "Warranty file is missing: $relativePath" }
    $file = Get-Item -LiteralPath $filePath -Force
    if (($file.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Warranty file must not be a link." }
    if ($file.Length -ne [long]$entry.bytes -or $file.Length -lt 0) { throw "Warranty file size mismatch: $relativePath" }
    $expectedHash = [string]$entry.sha256
    if ($expectedHash -cnotmatch '^[0-9a-fA-F]{64}$') { throw "Warranty SHA-256 is invalid." }
    $actualHash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash
    if ($actualHash -cne $expectedHash.ToUpperInvariant()) { throw "Warranty file SHA-256 mismatch: $relativePath" }
}

$actualNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
foreach ($item in @(Get-ChildItem -LiteralPath $uploadRoot -Force)) {
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.PSIsContainer) {
        throw "Warranty package contains a link, directory, or non-regular entry."
    }
    [void]$actualNames.Add($item.Name)
}
if ($actualNames.Count -ne $seen.Count) { throw "Warranty package file count does not match manifest." }
foreach ($name in $actualNames) {
    if (-not $seen.Contains($name)) { throw "Unlisted warranty file in package." }
}

$ids = New-Object 'System.Collections.Generic.HashSet[long]'
foreach ($reference in @($Uploads.references)) {
    if ($null -eq $reference -or @($reference.PSObject.Properties).Count -eq 0) { continue }
    $id = [long]$reference.warrantyId
    if ($id -le 0 -or -not $ids.Add($id)) { throw "Invalid or duplicate warranty reference ID." }
    if (-not $seen.Contains([string]$reference.relativePath)) { throw "Warranty reference has no packaged file." }
}
if ($ids.Count -ne [int]$Uploads.referenceCount) { throw "Warranty reference count does not match manifest." }
Write-Host "[OK] Warranty file integrity and manifest-only references checked: files=$($seen.Count), manifestReferences=$($ids.Count). SQL references are NOT verified."
