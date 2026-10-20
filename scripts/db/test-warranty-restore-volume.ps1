[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'warranty-restore-volume.ps1')
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-warranty-volume-test-' + [Guid]::NewGuid().ToString('N'))
$script:project = 'buildflowrestore0123456789ab'
$script:nonce = '0123456789abcdef0123456789abcdef'
$script:imageId = 'sha256:' + ('a' * 64)
$script:mode = 'ok'
$script:calls = @()
$script:pdfName = '0f86568e-8ac8-4226-b224-c0f9a7f72f3a.pdf'
function Assert-Rejected {
    param([scriptblock]$Action)
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw 'Expected rejection did not occur.' }
}
function Invoke-NativeProcess {
    param($FileName, $Arguments, $WorkingDirectory, [switch]$RejectStandardError)
    $script:calls += ,$Arguments
    if ($Arguments[0] -eq 'volume') {
        $labelNonce = if ($script:mode -eq 'wrong-owner') { 'wrong' } else { $script:nonce }
        $options = if ($script:mode -eq 'bind-volume') { @{ device = '/source'; type = 'none'; o = 'bind' } } else { $null }
        return [pscustomobject]@{ StandardOutput = (ConvertTo-Json -Depth 5 -InputObject @(@{
            Name = "$($script:project)_restore_warranty_uploads"; Driver = 'local'; Options = $options
            Labels = @{ 'com.docker.compose.project' = $script:project; 'com.docker.compose.volume' = 'restore_warranty_uploads'; 'com.buildflow.restore-test' = $labelNonce }
        })) }
    }
    if ($Arguments -contains "type=bind,source=$testRoot,target=/source,readonly") {
        if ($script:mode -eq 'copy-failure') { throw 'Target is not empty' }
        $copyCount = if ($script:mode -eq 'wrong-count') { '0:0' } else { "$($script:expected.Count):$($script:expected.Count)" }
        return [pscustomobject]@{ StandardOutput = "$copyCount`n" }
    }
    $hash = if ($script:mode -eq 'bad-hash') { '0' * 64 } else { $script:expected[0].sha256 }
    $scan = "$hash`t3`t$($script:pdfName)`n"
    if ($script:mode -eq 'extra-target') { $scan += "$hash`t3`t420e6e50-6b73-4aa8-b691-209e870e37be.pdf`n" }
    if ($script:mode -eq 'mutate-source') { [IO.File]::WriteAllText((Join-Path $testRoot $script:pdfName), 'xyz') }
    return [pscustomobject]@{ StandardOutput = $scan }
}
function Invoke-TestRestore {
    Invoke-WarrantyVolumeRestore -UploadsDirectory $testRoot -VolumeName "$($script:project)_restore_warranty_uploads" -WorkingDirectory $PSScriptRoot -ProjectName $script:project -Nonce $script:nonce -ImageId $script:imageId -ExpectedFiles $script:expected
}
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    [IO.File]::WriteAllText((Join-Path $testRoot $script:pdfName), 'abc')
    $script:expected = @(Get-WarrantyRestoreHostInventory -Directory $testRoot)
    $result = @(Invoke-TestRestore)
    if ($result.Count -ne 1 -or $script:calls.Count -ne 3) { throw 'Restore sequence mismatch.' }
    foreach ($call in @($script:calls | Where-Object { $_[0] -eq 'run' })) {
        if ([string]$call[-1] -cmatch "`r") { throw 'Restore helper passed CRLF to POSIX sh.' }
        foreach ($required in @('--network', 'none', '--cap-drop', 'ALL', '--read-only', 'no-new-privileges')) {
            if ($call -notcontains $required) { throw 'Missing sandbox flag.' }
        }
    }
    foreach ($case in @('wrong-owner', 'bind-volume', 'copy-failure', 'wrong-count', 'bad-hash', 'extra-target', 'mutate-source')) {
        $script:mode = $case
        Assert-Rejected { Invoke-TestRestore }
    }
    [IO.File]::WriteAllText((Join-Path $testRoot $script:pdfName), 'abc')
    $script:mode = 'ok'
    [IO.File]::WriteAllText((Join-Path $testRoot '420e6e50-6b73-4aa8-b691-209e870e37be.pdf'), 'abc')
    Assert-Rejected { Invoke-TestRestore }
    Remove-Item -LiteralPath (Join-Path $testRoot '420e6e50-6b73-4aa8-b691-209e870e37be.pdf')
    New-Item -ItemType Directory -Path (Join-Path $testRoot 'nested') | Out-Null
    Assert-Rejected { Get-WarrantyRestoreHostInventory -Directory $testRoot }
    Assert-Rejected { ConvertFrom-WarrantyVolumeScan -Text "garbage" }
    Assert-Rejected { Assert-WarrantyRestoreInventory -Actual $script:expected -Expected @($script:expected[0], $script:expected[0]) }
    Write-Host '[OK] Synthetic warranty volume restore tests passed; Docker was mocked and no engine was used.'
}
finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
