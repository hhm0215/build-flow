[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$scanner = Join-Path $PSScriptRoot "inventory-estimates.ps1"
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("buildflow-inventory-test-" + [Guid]::NewGuid().ToString("N"))
$source = Join-Path $testRoot "source"
$output = Join-Path $testRoot "output"
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Assert-Test {
    param([bool]$Condition, [string]$Code)
    if (-not $Condition) { throw "TEST_FAILED_$Code" }
}

try {
    [void][System.IO.Directory]::CreateDirectory($source)
    [void][System.IO.Directory]::CreateDirectory($output)
    [void][System.IO.Directory]::CreateDirectory((Join-Path $source "one"))
    [void][System.IO.Directory]::CreateDirectory((Join-Path $source "two"))
    [System.IO.File]::WriteAllText((Join-Path $source "a.xlsx"), "same bytes", $utf8)
    [System.IO.File]::WriteAllText((Join-Path $source "one\b.xlsx"), "same bytes", $utf8)
    $unicodeName = ([string][char]0xACAC) + ([string][char]0xC801) + ".xlsx"
    [System.IO.File]::WriteAllText((Join-Path (Join-Path $source "two") $unicodeName), "different bytes", $utf8)
    [System.IO.File]::WriteAllText((Join-Path $source "ignored.xls"), "legacy", $utf8)

    $sourceBefore = @(
        Get-ChildItem -LiteralPath $source -File -Recurse | Sort-Object FullName | ForEach-Object {
            [ordered]@{
                path = $_.FullName
                length = $_.Length
                modifiedAtUtc = $_.LastWriteTimeUtc.ToString("o")
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        }
    ) | ConvertTo-Json -Depth 3
    & $scanner -SourceDirectory $source -OutputRoot $output -SourceLabel "synthetic" -AllowSameVolumeForSyntheticTest | Out-Null
    $batches = @(Get-ChildItem -LiteralPath $output -Directory)
    Assert-Test ($batches.Count -eq 1) "BATCH_COUNT"
    $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $batches[0].FullName "manifest.json") | ConvertFrom-Json
    Assert-Test ($manifest.completedWithoutErrors -eq $true) "COMPLETION"
    Assert-Test ($manifest.selectedFileCount -eq 3) "FILE_COUNT"
    Assert-Test (@($manifest.entries | Where-Object status -eq "HASHED").Count -eq 3) "HASH_STATUS"
    Assert-Test (@($manifest.entries | Where-Object { $_.relativePath.EndsWith($unicodeName) }).Count -eq 1) "UNICODE_PATH"
    $hashes = @($manifest.entries | Sort-Object relativePath | ForEach-Object sha256)
    Assert-Test ((@($hashes | Select-Object -Unique)).Count -eq 2) "BYTE_IDENTITY"
    $sourceAfter = @(
        Get-ChildItem -LiteralPath $source -File -Recurse | Sort-Object FullName | ForEach-Object {
            [ordered]@{
                path = $_.FullName
                length = $_.Length
                modifiedAtUtc = $_.LastWriteTimeUtc.ToString("o")
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        }
    ) | ConvertTo-Json -Depth 3
    Assert-Test ($sourceBefore -ceq $sourceAfter) "SOURCE_METADATA"
    $acl = Get-Acl -LiteralPath $batches[0].FullName
    Assert-Test $acl.AreAccessRulesProtected "ACL_INHERITANCE"
    Assert-Test (@($acl.Access | Where-Object { $_.IsInherited }).Count -eq 0) "ACL_INHERITED_RULES"

    $rejected = $false
    try {
        & $scanner -SourceDirectory $source -OutputRoot $source -SourceLabel "synthetic" -AllowSameVolumeForSyntheticTest | Out-Null
    }
    catch { $rejected = ($_.Exception.Message -eq "OUTPUT_INSIDE_SOURCE") }
    Assert-Test $rejected "OUTPUT_INSIDE_SOURCE"

    & $scanner -SourceDirectory $source -OutputRoot $output -SourceLabel "synthetic" -AllowSameVolumeForSyntheticTest | Out-Null
    Assert-Test (@(Get-ChildItem -LiteralPath $output -Directory).Count -eq 2) "NO_OVERWRITE"

    $hiddenFile = Join-Path $source "hidden.xlsx"
    [System.IO.File]::WriteAllText($hiddenFile, "hidden source", $utf8)
    [System.IO.File]::SetAttributes($hiddenFile, [System.IO.FileAttributes]::Hidden)
    & $scanner -SourceDirectory $source -OutputRoot $output -SourceLabel "synthetic" -AllowSameVolumeForSyntheticTest | Out-Null
    $hiddenManifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path (Get-ChildItem -LiteralPath $output -Directory | Sort-Object CreationTimeUtc | Select-Object -Last 1).FullName "manifest.json") | ConvertFrom-Json
    Assert-Test (@($hiddenManifest.entries | Where-Object isHidden).Count -eq 1) "HIDDEN_FLAG"

    $limited = $false
    try {
        & $scanner -SourceDirectory $source -OutputRoot $output -SourceLabel "synthetic" -MaxFiles 1 -AllowSameVolumeForSyntheticTest | Out-Null
    }
    catch { $limited = ($_.Exception.Message -eq "INVENTORY_INCOMPLETE") }
    Assert-Test $limited "FILE_LIMIT"
    $limitedManifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path (Get-ChildItem -LiteralPath $output -Directory | Sort-Object CreationTimeUtc | Select-Object -Last 1).FullName "manifest.json") | ConvertFrom-Json
    Assert-Test (-not $limitedManifest.completedWithoutErrors -and $limitedManifest.errorCount -ge 1) "INCOMPLETE_MANIFEST"

    $outside = Join-Path $testRoot "outside"
    [void][System.IO.Directory]::CreateDirectory($outside)
    [System.IO.File]::WriteAllText((Join-Path $outside "not-source.xlsx"), "outside", $utf8)
    $link = Join-Path $source "linked"
    try { New-Item -ItemType Junction -Path $link -Target $outside -ErrorAction Stop | Out-Null }
    catch { throw "TEST_SETUP_JUNCTION_FAILED" }
    $junctionRejected = $false
    try {
        & $scanner -SourceDirectory $source -OutputRoot $output -SourceLabel "synthetic" -AllowSameVolumeForSyntheticTest | Out-Null
    }
    catch { $junctionRejected = ($_.Exception.Message -eq "INVENTORY_INCOMPLETE") }
    Assert-Test $junctionRejected "JUNCTION_REJECTION"
    $junctionManifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path (Get-ChildItem -LiteralPath $output -Directory | Sort-Object CreationTimeUtc | Select-Object -Last 1).FullName "manifest.json") | ConvertFrom-Json
    Assert-Test (@($junctionManifest.issues | Where-Object code -eq "REPARSE_POINT_REJECTED").Count -ge 1) "JUNCTION_ISSUE"
    Write-Host "[OK] Synthetic inventory, source immutability, ACL, limits, reparse guard, and no overwrite."
}
finally {
    $full = [System.IO.Path]::GetFullPath($testRoot)
    $temp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if ($full.StartsWith($temp, [System.StringComparison]::OrdinalIgnoreCase) -and
        [System.IO.Path]::GetFileName($full).StartsWith("buildflow-inventory-test-", [System.StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction SilentlyContinue
    }
}
