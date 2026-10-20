Set-StrictMode -Version Latest
$script:restoreWarrantyPattern = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.pdf$'

function Assert-WarrantyRestoreInventory {
    param([AllowEmptyCollection()][object[]]$Actual, [AllowEmptyCollection()][object[]]$Expected)
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($entry in @($Expected)) {
        if ($null -eq $entry -or @($entry.PSObject.Properties).Count -eq 0) { continue }
        $name = [string]$entry.relativePath
        if ($name -cnotmatch $script:restoreWarrantyPattern -or
            -not $seen.Add($name) -or [string]$entry.sha256 -cnotmatch '^[a-fA-F0-9]{64}$' -or [long]$entry.bytes -lt 0) {
            throw 'Invalid warranty restore inventory.'
        }
        $matches = @($Actual | Where-Object { $_.relativePath -ceq $name })
        if ($matches.Count -ne 1) {
            throw "Restored warranty inventory name match count differs (matches=$($matches.Count), actual=$(@($Actual).Count), expected=$(@($Expected).Count))."
        }
        if ([long]$matches[0].bytes -ne [long]$entry.bytes -or
            ([string]$matches[0].sha256).ToLowerInvariant() -cne ([string]$entry.sha256).ToLowerInvariant()) {
            throw 'Restored warranty inventory size or digest differs.'
        }
    }
    if (@($Actual).Count -ne $seen.Count) { throw 'Unexpected warranty restore files.' }
}

function Get-WarrantyRestoreHostInventory {
    param([Parameter(Mandatory = $true)][string]$Directory)
    $full = [IO.Path]::GetFullPath($Directory)
    $ancestor = Get-Item -LiteralPath $full -Force
    if (-not $ancestor.PSIsContainer) { throw 'Warranty source must be a directory.' }
    while ($null -ne $ancestor) {
        if (($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Warranty source ancestors must not be links.' }
        $ancestor = $ancestor.Parent
    }
    foreach ($item in @(Get-ChildItem -LiteralPath $full -Force)) {
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $item.Name -cnotmatch $script:restoreWarrantyPattern) {
            throw 'Warranty source contains an unsafe entry.'
        }
        [pscustomobject]@{ relativePath = $item.Name; bytes = $item.Length; sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
}

function ConvertFrom-WarrantyVolumeScan {
    param([AllowEmptyString()][string]$Text)
    foreach ($line in @($Text -split "`r?`n" | Where-Object { $_.Length -gt 0 })) {
        if ($line -cnotmatch '^(?<hash>[a-f0-9]{64})\t(?<bytes>[0-9]+)\t(?<name>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.pdf)$') {
            throw 'Malformed restored warranty scan.'
        }
        [pscustomobject]@{ relativePath = $Matches.name; bytes = [long]$Matches.bytes; sha256 = $Matches.hash }
    }
}

function Invoke-WarrantyVolumeRestore {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$UploadsDirectory,
        [Parameter(Mandatory = $true)][string]$VolumeName,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$ProjectName,
        [Parameter(Mandatory = $true)][string]$Nonce,
        [Parameter(Mandatory = $true)][string]$ImageId,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$ExpectedFiles
    )
    if ($ProjectName -cnotmatch '^buildflowrestore[a-f0-9]{12}$' -or $Nonce -cnotmatch '^[a-f0-9]{32}$' -or
        $ImageId -cnotmatch '^sha256:[a-f0-9]{64}$' -or
        $VolumeName -cne ($ProjectName + '_restore_warranty_uploads')) { throw 'Unsafe isolated warranty volume or helper image identity.' }
    $source = [IO.Path]::GetFullPath($UploadsDirectory)
    if ($source.Contains(',') -or $source.Contains('"') -or $source.Contains("`n") -or $source.Contains("`r")) { throw 'Unsafe warranty bind path.' }
    $before = @(Get-WarrantyRestoreHostInventory -Directory $source)
    try { Assert-WarrantyRestoreInventory -Actual $before -Expected $ExpectedFiles }
    catch { throw "Warranty source inventory check failed before copy: $($_.Exception.Message)" }
    $inspection = Invoke-NativeProcess -FileName docker -Arguments @('volume', 'inspect', $VolumeName) -WorkingDirectory $WorkingDirectory -RejectStandardError
    $volumes = @($inspection.StandardOutput | ConvertFrom-Json)
    if ($volumes.Count -ne 1) { throw 'Warranty volume inspection is ambiguous.' }
    $volume = $volumes[0]
    if ($volume.Name -cne $VolumeName -or $volume.Driver -cne 'local' -or
        ($null -ne $volume.Options -and @($volume.Options.PSObject.Properties).Count -ne 0) -or
        $volume.Labels.'com.docker.compose.project' -cne $ProjectName -or
        $volume.Labels.'com.docker.compose.volume' -cne 'restore_warranty_uploads' -or
        $volume.Labels.'com.buildflow.restore-test' -cne $Nonce) { throw 'Warranty volume ownership validation failed.' }
    $base = @('run', '--rm', '--pull=never', '--network', 'none', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--read-only', '--user', '0:0', '--label', "com.buildflow.restore-test=$Nonce")
    # Files are flat and names restricted before every shell operation. Globs also
    # cover hidden entries; any link or directory fails before the first copy.
    $copy = @'
set -eu
for f in /target/* /target/.[!.]* /target/..?*; do
  if [ -e "$f" ] || [ -L "$f" ]; then echo 'Target is not empty' >&2; exit 1; fi
done
for f in /source/* /source/.[!.]* /source/..?*; do
  [ -e "$f" ] || [ -L "$f" ] || continue
  [ -f "$f" ] && [ ! -L "$f" ] || exit 1
  n=${f##*/}
  printf '%s' "$n" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.pdf$' || exit 1
done
for f in /source/* /source/.[!.]* /source/..?*; do
  [ -e "$f" ] || continue
  cp -- "$f" "/target/${f##*/}"
done
'@
    $arguments = $base + @('--mount', "type=bind,source=$source,target=/source,readonly", '--mount', "type=volume,source=$VolumeName,target=/target,volume-nocopy", '--entrypoint', 'sh', $ImageId, '-c', $copy.Replace("`r`n", "`n").Replace("`r", "`n"))
    $copied = Invoke-NativeProcess -FileName docker -Arguments $arguments -WorkingDirectory $WorkingDirectory -RejectStandardError
    if (-not [string]::IsNullOrWhiteSpace($copied.StandardOutput)) { throw 'Unexpected warranty copy output.' }
    $scan = @'
set -eu
for f in /target/* /target/.[!.]* /target/..?*; do
  [ -e "$f" ] || [ -L "$f" ] || continue
  [ -f "$f" ] && [ ! -L "$f" ] || exit 1
  n=${f##*/}
  printf '%s' "$n" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.pdf$' || exit 1
  h=$(sha256sum -- "$f"); h=${h%% *}
  b=$(stat -c %s -- "$f")
  printf '%s\t%s\t%s\n' "$h" "$b" "$n"
done
'@
    $scanned = Invoke-NativeProcess -FileName docker -Arguments ($base + @('--mount', "type=volume,source=$VolumeName,target=/target,readonly,volume-nocopy", '--entrypoint', 'sh', $ImageId, '-c', $scan.Replace("`r`n", "`n").Replace("`r", "`n"))) -WorkingDirectory $WorkingDirectory -RejectStandardError
    $actual = @(ConvertFrom-WarrantyVolumeScan -Text $scanned.StandardOutput)
    try { Assert-WarrantyRestoreInventory -Actual $actual -Expected $ExpectedFiles }
    catch { throw "Isolated warranty volume inventory check failed after copy: $($_.Exception.Message)" }
    $after = @(Get-WarrantyRestoreHostInventory -Directory $source)
    try { Assert-WarrantyRestoreInventory -Actual $after -Expected $before }
    catch { throw "Warranty source inventory changed during copy: $($_.Exception.Message)" }
    return $actual
}
