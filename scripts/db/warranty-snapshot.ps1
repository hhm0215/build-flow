Set-StrictMode -Version Latest

function ConvertTo-WarrantyUploadManifest {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Rows,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Files,
        [Parameter(Mandatory = $true)][long]$ExpectedRowCount,
        [Parameter(Mandatory = $true)][object]$Source
    )

    if ($ExpectedRowCount -lt 0 -or $Rows.Count -ne $ExpectedRowCount) {
        throw 'Warranty DB row count changed or differs from inventory.'
    }
    foreach ($field in @('composeProject', 'volumeName', 'volumeDriver')) {
        $present = if ($Source -is [System.Collections.IDictionary]) { $Source.Contains($field) }
            else { $null -ne $Source.PSObject.Properties[$field] }
        if (-not $present -or [string]::IsNullOrWhiteSpace([string]$Source.$field)) {
            throw "Warranty source identity is missing: $field"
        }
    }
    if ([string]$Source.volumeDriver -cne 'local') { throw 'Warranty source volume must use the local driver.' }

    $namePattern = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.pdf$'
    $fileNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    $manifestFiles = New-Object 'System.Collections.Generic.List[object]'
    foreach ($file in $Files) {
        $present = $null -ne $file -and (
            ($file -is [System.Collections.IDictionary] -and $file.Contains('relativePath') -and $file.Contains('bytes') -and $file.Contains('sha256')) -or
            ($file -isnot [System.Collections.IDictionary] -and $null -ne $file.PSObject.Properties['relativePath'] -and
                $null -ne $file.PSObject.Properties['bytes'] -and $null -ne $file.PSObject.Properties['sha256'])
        )
        if (-not $present) {
            throw 'Warranty file metadata is incomplete.'
        }
        $name = [string]$file.relativePath
        if ($name -cnotmatch $namePattern -or -not $fileNames.Add($name)) {
            throw 'Unsafe or duplicate warranty file name.'
        }
        if (($file.bytes -isnot [int] -and $file.bytes -isnot [long]) -or [long]$file.bytes -lt 0 -or
            [string]$file.sha256 -cnotmatch '^[0-9a-f]{64}$') {
            throw 'Warranty file size or SHA-256 is invalid.'
        }
        $manifestFiles.Add([ordered]@{ relativePath = $name; bytes = [long]$file.bytes; sha256 = [string]$file.sha256 })
    }

    $ids = New-Object 'System.Collections.Generic.HashSet[long]'
    $referencedNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    $references = New-Object 'System.Collections.Generic.List[object]'
    foreach ($row in $Rows) {
        $present = $null -ne $row -and (
            ($row -is [System.Collections.IDictionary] -and $row.Contains('warrantyId') -and $row.Contains('filePath')) -or
            ($row -isnot [System.Collections.IDictionary] -and $null -ne $row.PSObject.Properties['warrantyId'] -and
                $null -ne $row.PSObject.Properties['filePath'])
        )
        if (-not $present) {
            throw 'Warranty DB row is incomplete.'
        }
        if (($row.warrantyId -isnot [int] -and $row.warrantyId -isnot [long]) -or
            [long]$row.warrantyId -le 0 -or -not $ids.Add([long]$row.warrantyId)) {
            throw 'Invalid or duplicate warranty DB ID.'
        }
        if ($null -eq $row.filePath) { continue }
        $path = [string]$row.filePath
        $prefix = '/app/uploads/warranties/'
        if (-not $path.StartsWith($prefix, [System.StringComparison]::Ordinal)) {
            throw 'Warranty DB file path is outside the expected upload mount.'
        }
        $name = $path.Substring($prefix.Length)
        if ($name -cnotmatch $namePattern -or -not $fileNames.Contains($name) -or
            -not $referencedNames.Add($name)) {
            throw 'Warranty DB file path is unsafe, missing, or shared by multiple rows.'
        }
        $references.Add([ordered]@{ warrantyId = [long]$row.warrantyId; relativePath = $name })
    }

    return [ordered]@{
        directory = 'uploads/warranties'
        mountPath = '/app/uploads/warranties'
        source = [ordered]@{
            composeProject = [string]$Source.composeProject
            volumeName = [string]$Source.volumeName
            volumeDriver = [string]$Source.volumeDriver
        }
        files = $manifestFiles.ToArray()
        references = $references.ToArray()
        referenceCount = $references.Count
        unreferencedFileCount = $fileNames.Count - $referencedNames.Count
    }
}

function Assert-RestoredWarrantyReferences {
    param(
        [Parameter(Mandatory = $true)][object]$Uploads,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Rows,
        [Parameter(Mandatory = $true)][long]$ExpectedRowCount
    )

    $files = @($Uploads.files | Where-Object { $null -ne $_ -and @($_.PSObject.Properties).Count -gt 0 })
    $actual = ConvertTo-WarrantyUploadManifest -Rows $Rows -Files $files -ExpectedRowCount $ExpectedRowCount -Source $Uploads.source
    if ([string]$Uploads.mountPath -cne [string]$actual.mountPath -or
        [string]$Uploads.directory -cne [string]$actual.directory -or
        [int]$Uploads.referenceCount -ne $actual.referenceCount -or
        [int]$Uploads.unreferencedFileCount -ne $actual.unreferencedFileCount) {
        throw 'Restored warranty reference metadata differs from the source manifest.'
    }
    $expected = @($Uploads.references | Where-Object { $null -ne $_ -and @($_.PSObject.Properties).Count -gt 0 } | Sort-Object warrantyId)
    $observed = @($actual.references | Sort-Object warrantyId)
    if ($expected.Count -ne $observed.Count) { throw 'Restored warranty reference count differs from the source manifest.' }
    for ($i = 0; $i -lt $expected.Count; $i++) {
        if (($expected[$i].warrantyId -isnot [int] -and $expected[$i].warrantyId -isnot [long]) -or
            [long]$expected[$i].warrantyId -ne [long]$observed[$i].warrantyId -or
            [string]$expected[$i].relativePath -cne [string]$observed[$i].relativePath) {
            throw 'Restored warranty DB file reference differs from the source manifest.'
        }
    }
}
