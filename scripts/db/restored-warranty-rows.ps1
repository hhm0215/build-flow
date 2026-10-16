Set-StrictMode -Version Latest

function Assert-NoReparseAncestor {
    param([Parameter(Mandatory = $true)][string]$Path)
    $item = Get-Item -LiteralPath $Path -Force
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Warranty export cannot use a linked or reparse directory."
        }
        $item = $item.Parent
    }
}

function Assert-PrivateWarrantyDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
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
