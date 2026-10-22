Set-StrictMode -Version Latest

function Assert-NoReparseAncestor {
    param([Parameter(Mandatory = $true)][string]$Path)
    $current = [IO.Path]::GetFullPath($Path)
    $childOwner = $null
    while (-not [string]::IsNullOrEmpty($current)) {
        $item = Get-Item -LiteralPath $current -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Warranty export cannot use a linked or reparse directory."
        }
        if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
            $metadata = Get-UnixPrivacyMetadata -Path $current
            if (-not (Test-SafeUnixAncestorMetadata -OwnerUid $metadata.OwnerUid -CurrentUid $metadata.CurrentUid `
                    -ChildOwnerUid $childOwner -OctalMode $metadata.OctalMode)) {
                throw 'Sensitive backup path has an untrusted ancestor that can replace its child.'
            }
            $childOwner = $metadata.OwnerUid
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}

function Test-SafeUnixAncestorMetadata {
    param(
        [Parameter(Mandatory = $true)][string]$OwnerUid,
        [Parameter(Mandatory = $true)][string]$CurrentUid,
        [AllowNull()][string]$ChildOwnerUid,
        [Parameter(Mandatory = $true)][string]$OctalMode
    )

    if ($OwnerUid -cnotmatch '^(0|[1-9][0-9]*)$' -or
        $CurrentUid -cnotmatch '^(0|[1-9][0-9]*)$' -or
        $OctalMode -cnotmatch '^[0-7]{3,4}$' -or
        $OwnerUid -notin @('0', $CurrentUid)) { return $false }
    $mode = [Convert]::ToInt32($OctalMode, 8)
    if (($mode -band 18) -eq 0) { return $true }
    # A sticky shared directory (such as /tmp) protects a child owned by us.
    return (($mode -band 512) -ne 0 -and $ChildOwnerUid -ceq $CurrentUid)
}

function Test-PrivateUnixMetadata {
    param(
        [Parameter(Mandatory = $true)][string]$OwnerUid,
        [Parameter(Mandatory = $true)][string]$CurrentUid,
        [Parameter(Mandatory = $true)][string]$OctalMode,
        [Parameter(Mandatory = $true)][bool]$IsDirectory
    )

    if ($OwnerUid -cnotmatch '^(0|[1-9][0-9]*)$' -or
        $CurrentUid -cnotmatch '^(0|[1-9][0-9]*)$' -or
        $OctalMode -cnotmatch '^[0-7]{3,4}$' -or $OwnerUid -cne $CurrentUid) { return $false }
    $mode = [Convert]::ToInt32($OctalMode, 8)
    # Reject group/other access and setuid/setgid/sticky bits. A writable
    # directory needs owner rwx; a backup file needs owner read/write.
    if (($mode -band 63) -ne 0 -or ($mode -band 3584) -ne 0) { return $false }
    if ($IsDirectory) { return ($mode -band 448) -eq 448 }
    return ($mode -band 448) -eq 384
}

function Get-UnixPrivacyMetadata {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        throw 'Unix privacy metadata is unavailable on Windows.'
    }
    $stat = @(& stat -c '%u:%a' -- $Path)
    if ($LASTEXITCODE -ne 0 -or $stat.Count -ne 1 -or $stat[0] -cnotmatch '^(0|[1-9][0-9]*):([0-7]{3,4})$') {
        throw 'Could not inspect Unix owner and mode for sensitive backup evidence.'
    }
    $ownerUid = ($stat[0] -split ':', 2)[0]
    $octalMode = ($stat[0] -split ':', 2)[1]
    $uid = @(& id -u)
    if ($LASTEXITCODE -ne 0 -or $uid.Count -ne 1 -or $uid[0] -cnotmatch '^(0|[1-9][0-9]*)$') {
        throw 'Could not inspect the current Unix user.'
    }
    return [pscustomobject]@{ OwnerUid = $ownerUid; CurrentUid = $uid[0]; OctalMode = $octalMode }
}

function Test-PrivateUnixUmaskValue {
    param([Parameter(Mandatory = $true)][string]$OctalUmask)
    if ($OctalUmask -cnotmatch '^[0-7]{3,4}$') { return $false }
    # The process must create 0700 directories and 0600 files from the outset.
    return [Convert]::ToInt32($OctalUmask, 8) -eq 63
}

function Assert-PrivateUnixUmask {
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { return }
    # Ubuntu exposes the current pwsh process mask without launching a child
    # with a potentially different mask. Other Unix hosts fail closed.
    if (-not (Test-Path -LiteralPath '/proc/self/status' -PathType Leaf)) {
        throw 'Unix process umask cannot be verified on this host.'
    }
    $lines = @(Get-Content -LiteralPath '/proc/self/status' | Where-Object { $_ -cmatch '^Umask:\s*([0-7]{4})$' })
    if ($lines.Count -ne 1 -or -not (Test-PrivateUnixUmaskValue -OctalUmask (($lines[0] -split ':', 2)[1].Trim()))) {
        throw 'Sensitive backup requires process umask 0077 before creating files.'
    }
}

function Assert-PrivateWarrantyDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        $item = Get-Item -LiteralPath $Path -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Sensitive backup evidence must not be a link.'
        }
        $metadata = Get-UnixPrivacyMetadata -Path $Path
        if (-not (Test-PrivateUnixMetadata -OwnerUid $metadata.OwnerUid -CurrentUid $metadata.CurrentUid `
                -OctalMode $metadata.OctalMode -IsDirectory $item.PSIsContainer)) {
            throw 'Sensitive backup evidence must be owned by the current user and private (0700 directory or 0600 file).'
        }
        return
    }
    $allowed = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')
    foreach ($rule in (Get-Acl -LiteralPath $Path).Access) {
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow) {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if ($sid -notin $allowed) { throw "Warranty export location grants another principal access to sensitive evidence." }
        }
    }
}

function ConvertFrom-RestoredWarrantyLines {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Lines, [Parameter(Mandatory = $true)][long]$ExpectedCount)

    if ($ExpectedCount -lt 0 -or $Lines.Count -ne $ExpectedCount) {
        throw "Restored warranty row count differs from the backup manifest."
    }
    $ids = New-Object 'System.Collections.Generic.HashSet[long]'
    $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
    $rows = New-Object 'System.Collections.Generic.List[object]'
    foreach ($line in $Lines) {
        $parts = $line -split "`t", 2
        if ($parts.Count -ne 2 -or $parts[0] -cnotmatch '^[1-9][0-9]*$' -or
            $parts[1] -cnotmatch '^(N|H(?:[0-9A-F]{2})*)$') {
            throw "Malformed restored warranty row export."
        }
        $id = 0L
        if (-not [long]::TryParse($parts[0], [ref]$id) -or -not $ids.Add($id)) {
            throw "Invalid or duplicate restored warranty ID."
        }
        $filePath = $null
        if ($parts[1].StartsWith('H', [StringComparison]::Ordinal)) {
            $hex = $parts[1].Substring(1)
            $bytes = New-Object byte[] ($hex.Length / 2)
            for ($i = 0; $i -lt $bytes.Length; $i++) {
                $bytes[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16)
            }
            $filePath = $utf8.GetString($bytes)
        }
        $rows.Add([ordered]@{ warrantyId = $id; filePath = $filePath })
    }
    return $rows.ToArray()
}

function Write-RestoredWarrantyRows {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][object[]]$Rows,
        [Parameter(Mandatory = $true)][object]$Evidence
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $parent = [IO.Path]::GetDirectoryName($fullPath)
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        throw "Warranty export parent directory must already exist."
    }
    Assert-NoReparseAncestor -Path $parent
    Assert-PrivateWarrantyDirectory -Path $parent
    Assert-PrivateUnixUmask
    $evidencePath = "$fullPath.evidence.json"
    if ((Test-Path -LiteralPath $fullPath) -or (Test-Path -LiteralPath $evidencePath)) {
        throw "Warranty export or evidence already exists; refusing overwrite."
    }
    $encoding = New-Object System.Text.UTF8Encoding($false, $true)
    $created = $false
    $evidenceCreated = $false
    try {
        $json = ConvertTo-Json -InputObject $Rows -Depth 3 -Compress
        $stream = New-Object IO.FileStream($fullPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $created = $true
        try {
            $bytes = $encoding.GetBytes($json)
            $stream.Write($bytes, 0, $bytes.Length)
        }
        finally { $stream.Dispose() }
        Assert-PrivateWarrantyDirectory -Path $fullPath
        $Evidence.rowsSha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $metadata = ConvertTo-Json -InputObject $Evidence -Depth 5 -Compress
        $stream = New-Object IO.FileStream($evidencePath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $evidenceCreated = $true
        try {
            $bytes = $encoding.GetBytes($metadata)
            $stream.Write($bytes, 0, $bytes.Length)
        }
        finally { $stream.Dispose() }
        Assert-PrivateWarrantyDirectory -Path $evidencePath
    }
    catch {
        if ($evidenceCreated) { Remove-Item -LiteralPath $evidencePath -Force -ErrorAction SilentlyContinue }
        if ($created) { Remove-Item -LiteralPath $fullPath -Force -ErrorAction SilentlyContinue }
        throw
    }
}
