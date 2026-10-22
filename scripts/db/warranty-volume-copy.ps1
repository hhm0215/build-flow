Set-StrictMode -Version Latest

$script:warrantyFilePattern = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.pdf$'
$script:warrantyScanShell = @'
set -eu
for path in /source/* /source/.[!.]* /source/..?*; do
  if [ ! -e "$path" ] && [ ! -L "$path" ]; then continue; fi
  if [ ! -f "$path" ] || [ -L "$path" ]; then exit 41; fi
  name=${path##*/}
  printf '%s' "$name" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.pdf$' || exit 42
  bytes=$(wc -c < "$path")
  digest=$(sha256sum "$path")
  digest=${digest%% *}
  printf '%s\t%s\t%s\n' "$name" "$bytes" "$digest"
done
'@
$script:warrantyCopyShell = @'
set -eu
umask 077
for path in /source/* /source/.[!.]* /source/..?*; do
  if [ ! -e "$path" ] && [ ! -L "$path" ]; then continue; fi
  if [ ! -f "$path" ] || [ -L "$path" ]; then exit 41; fi
  name=${path##*/}
  printf '%s' "$name" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.pdf$' || exit 42
  if [ -e "/target/$name" ] || [ -L "/target/$name" ]; then exit 43; fi
  cp -- "$path" "/target/$name"
done
'@

function ConvertFrom-WarrantyVolumeListing {
    param([AllowEmptyString()][Parameter(Mandatory = $true)][string]$Text)

    $files = New-Object 'System.Collections.Generic.List[object]'
    $names = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    foreach ($line in @(Get-OutputLines -Text $Text)) {
        $parts = $line -split "`t", 3
        if ($parts.Count -ne 3 -or $parts[0] -cnotmatch $script:warrantyFilePattern -or
            $parts[1] -cnotmatch '^(0|[1-9][0-9]*)$' -or $parts[2] -cnotmatch '^[0-9a-f]{64}$' -or
            -not $names.Add($parts[0])) {
            throw 'Invalid or duplicate warranty volume inventory entry.'
        }
        $length = 0L
        if (-not [long]::TryParse($parts[1], [ref]$length)) { throw 'Warranty volume file size is out of range.' }
        $files.Add([ordered]@{ relativePath = $parts[0]; bytes = $length; sha256 = $parts[2] })
    }
    return @($files.ToArray() | Sort-Object relativePath)
}

function Assert-CopiedWarrantyFiles {
    param([Parameter(Mandatory = $true)][string]$Directory, [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$ExpectedFiles)

    $actual = @(Get-ChildItem -LiteralPath $Directory -Force)
    if ($actual.Count -ne $ExpectedFiles.Count) { throw 'Copied warranty file count differs from source volume.' }
    $expectedNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    foreach ($file in $ExpectedFiles) {
        [void]$expectedNames.Add([string]$file.relativePath)
        $path = Join-Path $Directory ([string]$file.relativePath)
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Copied warranty file is missing.' }
        $item = Get-Item -LiteralPath $path -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $item.Length -ne [long]$file.bytes -or
            (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$file.sha256) {
            throw 'Copied warranty file differs from source volume inventory.'
        }
    }
    foreach ($item in $actual) {
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            -not $expectedNames.Contains($item.Name)) {
            throw 'Copied warranty package contains an unexpected entry.'
        }
    }
}

function Invoke-WarrantyVolumeHelper {
    param(
        [Parameter(Mandatory = $true)][string]$VolumeName,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$ImageId,
        [Parameter(Mandatory = $true)][string]$Shell,
        [string]$DestinationDirectory
    )

    if ($VolumeName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$') { throw 'Unsafe warranty volume name.' }
    if ($ImageId -cnotmatch '^sha256:[a-f0-9]{64}$') { throw 'Warranty helper requires a pinned source image ID.' }
    $arguments = @('run', '--rm', '--pull=never', '--network', 'none', '--read-only', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--mount', "type=volume,source=$VolumeName,target=/source,readonly")
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        $uid = @(& id -u)
        if ($LASTEXITCODE -ne 0 -or $uid.Count -ne 1 -or $uid[0] -cnotmatch '^(0|[1-9][0-9]*)$') {
            throw 'Cannot verify Unix user identity for warranty volume copy.'
        }
        $gid = @(& id -g)
        if ($LASTEXITCODE -ne 0 -or $gid.Count -ne 1 -or $gid[0] -cnotmatch '^(0|[1-9][0-9]*)$') {
            throw 'Cannot verify Unix group identity for warranty volume copy.'
        }
        $arguments += @('--user', "$($uid[0]):$($gid[0])")
    }
    if (-not [string]::IsNullOrWhiteSpace($DestinationDirectory)) {
        $arguments += @('--mount', "type=bind,source=$DestinationDirectory,target=/target")
    }
    # Windows PowerShell preserves CRLF in here-strings; POSIX sh treats the
    # carriage return after `set -eu` as part of the option name.
    $unixShell = $Shell.Replace("`r`n", "`n").Replace("`r", "`n")
    $arguments += @('--entrypoint', 'sh', $ImageId, '-c', $unixShell)
    return (Invoke-NativeProcess -FileName 'docker' -Arguments $arguments -WorkingDirectory $WorkingDirectory -RejectStandardError).StandardOutput
}

function Copy-WarrantyVolumeSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$VolumeName,
        [Parameter(Mandatory = $true)][string]$DestinationDirectory,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$ImageId
    )

    $destination = [IO.Path]::GetFullPath($DestinationDirectory)
    if (-not (Test-Path -LiteralPath $destination -PathType Container) -or
        @(Get-ChildItem -LiteralPath $destination -Force).Count -ne 0) {
        throw 'Warranty snapshot destination must be an existing empty directory.'
    }
    $item = Get-Item -LiteralPath $destination -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Warranty snapshot destination cannot be a linked directory.'
    }
    $before = @(ConvertFrom-WarrantyVolumeListing -Text (Invoke-WarrantyVolumeHelper -VolumeName $VolumeName -WorkingDirectory $WorkingDirectory -ImageId $ImageId -Shell $script:warrantyScanShell))
    [void](Invoke-WarrantyVolumeHelper -VolumeName $VolumeName -WorkingDirectory $WorkingDirectory -ImageId $ImageId -Shell $script:warrantyCopyShell -DestinationDirectory $destination)
    $after = @(ConvertFrom-WarrantyVolumeListing -Text (Invoke-WarrantyVolumeHelper -VolumeName $VolumeName -WorkingDirectory $WorkingDirectory -ImageId $ImageId -Shell $script:warrantyScanShell))
    if ((ConvertTo-Json -InputObject $before -Depth 3 -Compress) -cne (ConvertTo-Json -InputObject $after -Depth 3 -Compress)) {
        throw 'Warranty source volume changed during snapshot; retain the incomplete copy for inspection.'
    }
    Assert-CopiedWarrantyFiles -Directory $destination -ExpectedFiles $before
    return $before
}
