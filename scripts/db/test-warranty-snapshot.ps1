[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'warranty-snapshot.ps1')
. (Join-Path $PSScriptRoot 'warranty-volume-copy.ps1')

function Assert-Rejected {
    param([scriptblock]$Action, [string]$Pattern)
    $rejected = $false
    try { & $Action | Out-Null }
    catch { $rejected = $_.Exception.Message -match $Pattern }
    if (-not $rejected) { throw "Expected warranty snapshot rejection: $Pattern" }
}

$name = '0f86568e-8ac8-4226-b224-c0f9a7f72f3a.pdf'
$orphan = '420e6e50-6b73-4aa8-b691-209e870e37be.pdf'
$source = [pscustomobject]@{ composeProject = 'test'; volumeName = 'test_warranty_uploads'; volumeDriver = 'local' }
$emptyManifest = ConvertTo-WarrantyUploadManifest -Rows @() -Files @() -ExpectedRowCount 0 -Source $source
if ($emptyManifest.referenceCount -ne 0 -or @($emptyManifest.files).Count -ne 0) { throw 'Empty warranty snapshot was not accepted.' }
$files = @(
    [pscustomobject]@{ relativePath = $name; bytes = [long]10; sha256 = ('a' * 64) },
    [pscustomobject]@{ relativePath = $orphan; bytes = [long]5; sha256 = ('b' * 64) }
)
$rows = @(
    [pscustomobject]@{ warrantyId = [long]1; filePath = "/app/uploads/warranties/$name" },
    [pscustomobject]@{ warrantyId = [long]2; filePath = $null }
)
$manifest = ConvertTo-WarrantyUploadManifest -Rows $rows -Files $files -ExpectedRowCount 2 -Source $source
if ($manifest.referenceCount -ne 1 -or $manifest.unreferencedFileCount -ne 1 -or @($manifest.files).Count -ne 2) {
    throw 'Warranty snapshot did not preserve referenced and orphan files.'
}
Assert-RestoredWarrantyReferences -Uploads $manifest -Rows $rows -ExpectedRowCount 2
Assert-RestoredWarrantyReferences -Uploads $emptyManifest -Rows @() -ExpectedRowCount 0
Assert-Rejected { Assert-RestoredWarrantyReferences -Uploads $manifest -Rows @([pscustomobject]@{ warrantyId = 1L; filePath = $null }, $rows[1]) -ExpectedRowCount 2 } 'differs'
Assert-Rejected { Assert-RestoredWarrantyReferences -Uploads $manifest -Rows @($rows[0]) -ExpectedRowCount 2 } 'row count'
Assert-Rejected { ConvertTo-WarrantyUploadManifest $rows $files 1 $source } 'row count'
Assert-Rejected { ConvertTo-WarrantyUploadManifest @($rows[0], $rows[0]) $files 2 $source } 'duplicate warranty DB ID'
Assert-Rejected { ConvertTo-WarrantyUploadManifest @([pscustomobject]@{ warrantyId = 1L; filePath = '/other/file.pdf' }) $files 1 $source } 'outside'
Assert-Rejected { ConvertTo-WarrantyUploadManifest @([pscustomobject]@{ warrantyId = 1L; filePath = '/app/uploads/warranties/missing.pdf' }) $files 1 $source } 'unsafe, missing'
Assert-Rejected { ConvertTo-WarrantyUploadManifest @([pscustomobject]@{ warrantyId = 1L; filePath = "/app/uploads/warranties/$name" }, [pscustomobject]@{ warrantyId = 2L; filePath = "/app/uploads/warranties/$name" }) $files 2 $source } 'shared'
Assert-Rejected { ConvertTo-WarrantyUploadManifest $rows @($files[0], $files[0]) 2 $source } 'duplicate warranty file'
Assert-Rejected { ConvertTo-WarrantyUploadManifest $rows @([pscustomobject]@{ relativePath = '../escape.pdf'; bytes = 1L; sha256 = ('a' * 64) }) 2 $source } 'Unsafe'
$source.volumeDriver = 'remote'
Assert-Rejected { ConvertTo-WarrantyUploadManifest $rows $files 2 $source } 'local driver'
$source.volumeDriver = 'local'

$copyRoot = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-warranty-snapshot-test-' + [Guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $copyRoot | Out-Null
    Assert-CopiedWarrantyFiles -Directory $copyRoot -ExpectedFiles @()
    $copyPath = Join-Path $copyRoot $name
    [IO.File]::WriteAllText($copyPath, 'synthetic')
    $hash = (Get-FileHash -LiteralPath $copyPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $copyPath).Length
    $listing = "$name`t$size`t$hash`n"
    $parsed = @(ConvertFrom-WarrantyVolumeListing -Text $listing)
    if ($parsed.Count -ne 1 -or $parsed[0].relativePath -cne $name) { throw 'Volume listing parser did not retain the file.' }
    Assert-CopiedWarrantyFiles -Directory $copyRoot -ExpectedFiles $parsed
    Assert-Rejected { ConvertFrom-WarrantyVolumeListing -Text ($listing + $listing) } 'duplicate'
    Assert-Rejected { ConvertFrom-WarrantyVolumeListing -Text "../escape.pdf`t1`t$hash" } 'Invalid'
    Assert-Rejected { ConvertFrom-WarrantyVolumeListing -Text "$name`t999999999999999999999999`t$hash" } 'out of range'
    Assert-Rejected { Assert-CopiedWarrantyFiles -Directory $copyRoot -ExpectedFiles @([pscustomobject]@{ relativePath = $name; bytes = [long]$size; sha256 = ('a' * 64) }) } 'differs'
}
finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
    $resolved = [IO.Path]::GetFullPath($copyRoot)
    if (-not $resolved.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Synthetic cleanup target escaped the temporary directory.'
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
Write-Host '[OK] Warranty snapshot manifest and DB-reference synthetic tests passed.'
