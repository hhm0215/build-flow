Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'restored-warranty-rows.ps1')

function Assert-Rejected {
    param([Parameter(Mandatory = $true)][scriptblock]$Action)
    $rejected = $false
    try { & $Action }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Malformed restored warranty export was accepted.' }
}

$path = "/app/uploads/warranties/한글`n탭`t.pdf"
$hex = [BitConverter]::ToString(([Text.Encoding]::UTF8).GetBytes($path)).Replace('-', '')
$rows = @(ConvertFrom-RestoredWarrantyLines -Lines @("1`tH$hex", "2`tN", "3`tH") -ExpectedCount 3)
if ($rows.Count -ne 3 -or $rows[0].filePath -cne $path -or $null -ne $rows[1].filePath -or $rows[2].filePath -cne '') {
    throw 'Warranty row conversion did not preserve UTF-8, delimiters, null and empty values.'
}
$empty = @(ConvertFrom-RestoredWarrantyLines -Lines @() -ExpectedCount 0)
if ($empty.Count -ne 0) { throw 'Empty warranty table conversion failed.' }
Assert-Rejected { ConvertFrom-RestoredWarrantyLines -Lines @("1`tN", "1`tN") -ExpectedCount 2 }
Assert-Rejected { ConvertFrom-RestoredWarrantyLines -Lines @("0`tN") -ExpectedCount 1 }
Assert-Rejected { ConvertFrom-RestoredWarrantyLines -Lines @("a`tN") -ExpectedCount 1 }
Assert-Rejected { ConvertFrom-RestoredWarrantyLines -Lines @("1`tH0") -ExpectedCount 1 }
Assert-Rejected { ConvertFrom-RestoredWarrantyLines -Lines @("1`tHFF") -ExpectedCount 1 }
Assert-Rejected { ConvertFrom-RestoredWarrantyLines -Lines @("1`tN`tX") -ExpectedCount 1 }
Assert-Rejected { ConvertFrom-RestoredWarrantyLines -Lines @("1`tN") -ExpectedCount 2 }

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("buildflow-warranty-row-test-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $acl = Get-Acl -LiteralPath $testRoot
    $acl.SetAccessRuleProtection($true, $false)
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $rights = [Security.AccessControl.FileSystemRights]::FullControl
    $inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($user, $rights, $inheritance, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $testRoot -AclObject $acl
    $exportPath = Join-Path $testRoot 'rows.json'
    $evidence = [ordered]@{ kind = 'synthetic'; rowsSha256 = $null }
    Write-RestoredWarrantyRows -Path $exportPath -Rows $rows -Evidence $evidence
    $savedRows = [IO.File]::ReadAllText($exportPath) | ConvertFrom-Json
    $savedEvidence = [IO.File]::ReadAllText("$exportPath.evidence.json") | ConvertFrom-Json
    if (@($savedRows).Count -ne 3 -or $savedRows[0].filePath -cne $path -or
        $savedEvidence.rowsSha256 -cne (Get-FileHash -LiteralPath $exportPath -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'Warranty row export or evidence hash differs.'
    }
    $before = [IO.File]::ReadAllText($exportPath)
    Assert-Rejected { Write-RestoredWarrantyRows -Path $exportPath -Rows $rows -Evidence $evidence }
    if ([IO.File]::ReadAllText($exportPath) -cne $before) {
        throw 'Warranty export changed after overwrite rejection.'
    }
    Write-Host '[OK] Restored warranty row parser and protected export synthetic tests passed.'
}
finally {
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if ($resolved.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -like 'buildflow-warranty-row-test-*') {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
