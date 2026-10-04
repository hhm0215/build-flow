[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceDirectory,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$SourceLabel = "estimate-2026",
    [int]$MaxFiles = 1000,
    [long]$MaxBytes = 104857600,
    [switch]$AllowSameVolumeForSyntheticTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-NoReparseAncestor {
    param([Parameter(Mandatory = $true)][string]$Path)
    $cursor = [System.IO.Path]::GetFullPath($Path)
    while ($null -ne $cursor) {
        try { $attributes = [System.IO.File]::GetAttributes($cursor) }
        catch { throw "PATH_CHECK_FAILED" }
        if (($attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "REPARSE_POINT_REJECTED"
        }
        $parent = [System.IO.Directory]::GetParent($cursor)
        if ($null -eq $parent) { break }
        $cursor = $parent.FullName
    }
}

function Set-PrivateDirectoryAcl {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        $acl = Get-Acl -LiteralPath $Path
        $acl.SetAccessRuleProtection($true, $false)
        foreach ($rule in @($acl.Access)) { [void]$acl.RemoveAccessRuleSpecific($rule) }
        $identities = @(
            [System.Security.Principal.WindowsIdentity]::GetCurrent().User,
            (New-Object System.Security.Principal.SecurityIdentifier("S-1-5-18")),
            (New-Object System.Security.Principal.SecurityIdentifier("S-1-5-32-544"))
        )
        foreach ($identity in $identities) {
            $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
                $identity,
                [System.Security.AccessControl.FileSystemRights]::FullControl,
                ([System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit),
                [System.Security.AccessControl.PropagationFlags]::None,
                [System.Security.AccessControl.AccessControlType]::Allow
            )
            $acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $Path -AclObject $acl
    }
    catch { throw "PRIVATE_ACL_FAILED" }
}

if ($SourceLabel -cnotmatch '^[A-Za-z0-9_-]{1,64}$' -or $MaxFiles -lt 1 -or $MaxBytes -lt 1) {
    throw "INVALID_PARAMETERS"
}
try {
    $source = [System.IO.Path]::GetFullPath($SourceDirectory).TrimEnd('\', '/')
    $output = [System.IO.Path]::GetFullPath($OutputRoot).TrimEnd('\', '/')
} catch { throw "INVALID_PATH" }
if ($source.StartsWith('\\') -or $output.StartsWith('\\')) { throw "UNC_PATH_REJECTED" }
if (-not [System.IO.Directory]::Exists($source) -or -not [System.IO.Directory]::Exists($output)) {
    throw "DIRECTORY_NOT_FOUND"
}
try { $sourceDrive = New-Object System.IO.DriveInfo([System.IO.Path]::GetPathRoot($source)) }
catch { throw "SOURCE_DRIVE_CHECK_FAILED" }
if ($sourceDrive.DriveType -eq [System.IO.DriveType]::Network -or
    $sourceDrive.DriveType -eq [System.IO.DriveType]::NoRootDirectory) {
    throw "SOURCE_MUST_BE_LOCAL_DRIVE"
}
try { $outputDrive = New-Object System.IO.DriveInfo([System.IO.Path]::GetPathRoot($output)) }
catch { throw "OUTPUT_DRIVE_CHECK_FAILED" }
if ($outputDrive.DriveType -ne [System.IO.DriveType]::Fixed) {
    throw "OUTPUT_MUST_BE_LOCAL_FIXED_DRIVE"
}
if ([System.IO.Path]::GetPathRoot($source).Equals([System.IO.Path]::GetPathRoot($output), [System.StringComparison]::OrdinalIgnoreCase)) {
    $temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $AllowSameVolumeForSyntheticTest -or
        -not $source.StartsWith($temporaryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "OUTPUT_MUST_BE_ON_DIFFERENT_VOLUME"
    }
}
if ($output.Equals($source, [System.StringComparison]::OrdinalIgnoreCase) -or
    $output.StartsWith(($source + [System.IO.Path]::DirectorySeparatorChar), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OUTPUT_INSIDE_SOURCE"
}
Assert-NoReparseAncestor -Path $source
Assert-NoReparseAncestor -Path $output

$batchId = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ") + "-" + [Guid]::NewGuid().ToString("N")
$batchDirectory = Join-Path $output $batchId
try {
    [void][System.IO.Directory]::CreateDirectory($batchDirectory)
    Set-PrivateDirectoryAcl -Path $batchDirectory
} catch { throw "OUTPUT_PREPARATION_FAILED" }

$entries = New-Object System.Collections.Generic.List[object]
$issues = New-Object System.Collections.Generic.List[object]
$pending = New-Object System.Collections.Generic.Stack[string]
$pending.Push($source)
$selectedBytes = [long]0
$errorCount = 0
$limitReached = $false
$startedAt = [DateTime]::UtcNow.ToString("o")
while ($pending.Count -gt 0) {
    $directory = $pending.Pop()
    try {
        Assert-NoReparseAncestor -Path $directory
        $children = @(Get-ChildItem -LiteralPath $directory -Force -ErrorAction Stop)
    }
    catch {
        $errorCount++
        $issues.Add([ordered]@{
            relativePath = $directory.Substring($source.Length).TrimStart('\', '/')
            code = "DIRECTORY_READ_FAILED"
        })
        continue
    }
    foreach ($child in $children) {
        if (($child.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            $errorCount++
            $issues.Add([ordered]@{
                relativePath = $child.FullName.Substring($source.Length).TrimStart('\', '/')
                code = "REPARSE_POINT_REJECTED"
            })
            continue
        }
        if ($child.PSIsContainer) {
            $pending.Push($child.FullName)
            continue
        }
        if (-not $child.Extension.Equals(".xlsx", [System.StringComparison]::OrdinalIgnoreCase)) { continue }
        $relativePath = $child.FullName.Substring($source.Length).TrimStart('\', '/')
        $entry = [ordered]@{
            relativePath = $relativePath
            observedBytes = [long]$child.Length
            observedModifiedAtUtc = $child.LastWriteTimeUtc.ToString("o")
            isHidden = [bool](($child.Attributes -band [System.IO.FileAttributes]::Hidden) -ne 0)
            status = "PENDING"
            sha256 = $null
        }
        if ($entries.Count -ge $MaxFiles -or $selectedBytes + [long]$child.Length -gt $MaxBytes) {
            $entry.status = "LIMIT_EXCEEDED"
            $errorCount++
            $entries.Add($entry)
            $limitReached = $true
            break
        }
        $selectedBytes += [long]$child.Length
        try {
            Assert-NoReparseAncestor -Path $child.FullName
            $beforeLength = [long]$child.Length
            $beforeTime = $child.LastWriteTimeUtc
            $stream = New-Object System.IO.FileStream(
                $child.FullName,
                [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::Read,
                [System.IO.FileShare]::Read
            )
            try {
                if ($stream.Length -ne $beforeLength -or $stream.Length -gt ($MaxBytes - ($selectedBytes - $beforeLength))) {
                    $entry.status = "CHANGED"
                    $errorCount++
                }
                else {
                    $hasher = [System.Security.Cryptography.SHA256]::Create()
                    try { $hash = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace("-", "").ToLowerInvariant() }
                    finally { $hasher.Dispose() }
                }
            }
            finally { $stream.Dispose() }
            if ($entry.status -eq "PENDING") {
                $after = Get-Item -LiteralPath $child.FullName -Force -ErrorAction Stop
                if (($after.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or
                    $after.Length -ne $beforeLength -or $after.LastWriteTimeUtc -ne $beforeTime) {
                    $entry.status = "CHANGED"
                    $errorCount++
                }
                else {
                    $entry.status = "HASHED"
                    $entry.sha256 = $hash
                }
            }
        }
        catch {
            $entry.status = "READ_FAILED"
            $errorCount++
        }
        $entries.Add($entry)
    }
    if ($limitReached) { break }
}

$manifest = [ordered]@{
    formatVersion = 1
    batchId = $batchId
    sourceLabel = $SourceLabel
    startedAtUtc = $startedAt
    finishedAtUtc = [DateTime]::UtcNow.ToString("o")
    completedWithoutErrors = ($errorCount -eq 0)
    selectedFileCount = $entries.Count
    selectedBytes = $selectedBytes
    errorCount = $errorCount
    entries = @($entries | Sort-Object { $_.relativePath })
    issues = @($issues | Sort-Object { $_.relativePath })
}
$temporaryPath = Join-Path $batchDirectory "manifest.partial.json"
$finalPath = Join-Path $batchDirectory "manifest.json"
try {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $json = $manifest | ConvertTo-Json -Depth 5
    $stream = New-Object System.IO.FileStream(
        $temporaryPath,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    try {
        $bytes = $utf8.GetBytes($json)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    }
    finally { $stream.Dispose() }
    Move-Item -LiteralPath $temporaryPath -Destination $finalPath -ErrorAction Stop
} catch { throw "MANIFEST_WRITE_FAILED" }

Write-Host "Inventory batch: $batchId"
Write-Host "Selected files: $($entries.Count); bytes: $selectedBytes; errors: $errorCount"
if ($errorCount -gt 0) { throw "INVENTORY_INCOMPLETE" }
