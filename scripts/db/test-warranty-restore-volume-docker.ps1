[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'warranty-restore-volume.ps1')

$project = 'buildflowrestore' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$nonce = [Guid]::NewGuid().ToString('N')
$volumeName = $project + '_restore_warranty_uploads'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-warranty-docker-test-' + [Guid]::NewGuid().ToString('N'))
$created = $false
$name = '0f86568e-8ac8-4226-b224-c0f9a7f72f3a.pdf'
try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    [IO.File]::WriteAllText((Join-Path $testRoot $name), 'synthetic-warranty-volume-restore')
    $expected = @(Get-WarrantyRestoreHostInventory -Directory $testRoot)
    $image = (Invoke-NativeProcess -FileName docker -Arguments @('image', 'inspect', 'mysql:8.0', '--format', '{{.Id}}') -WorkingDirectory $PSScriptRoot -RejectStandardError).StandardOutput.Trim()
    if ($image -cnotmatch '^sha256:[a-f0-9]{64}$') { throw 'Local MySQL helper image identity is invalid.' }
    $output = (Invoke-NativeProcess -FileName docker -Arguments @('volume', 'create',
        '--label', "com.docker.compose.project=$project",
        '--label', 'com.docker.compose.volume=restore_warranty_uploads',
        '--label', "com.buildflow.restore-test=$nonce", $volumeName) -WorkingDirectory $PSScriptRoot -RejectStandardError).StandardOutput.Trim()
    if ($output -cne $volumeName) { throw 'Docker created an unexpected test volume.' }
    $created = $true
    $actual = @(Invoke-WarrantyVolumeRestore -UploadsDirectory $testRoot -VolumeName $volumeName -WorkingDirectory $PSScriptRoot -ProjectName $project -Nonce $nonce -ImageId $image -ExpectedFiles $expected)
    Assert-WarrantyRestoreInventory -Actual $actual -Expected $expected
    Write-Host '[OK] Docker warranty volume copy and independent SHA-256 scan passed with synthetic data.'
}
finally {
    if ($created) {
        $inspectText = (Invoke-NativeProcess -FileName docker -Arguments @('volume', 'inspect', $volumeName) -WorkingDirectory $PSScriptRoot -RejectStandardError).StandardOutput
        $inspection = @($inspectText | ConvertFrom-Json) | Select-Object -First 1
        if ([string]$inspection.Name -cne $volumeName -or
            [string]$inspection.Labels.'com.docker.compose.project' -cne $project -or
            [string]$inspection.Labels.'com.docker.compose.volume' -cne 'restore_warranty_uploads' -or
            [string]$inspection.Labels.'com.buildflow.restore-test' -cne $nonce) {
            throw 'Synthetic Docker volume identity changed; preserving it for inspection.'
        }
        [void](Invoke-NativeProcess -FileName docker -Arguments @('volume', 'rm', $volumeName) -WorkingDirectory $PSScriptRoot -RejectStandardError)
    }
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Synthetic test path escaped the temporary directory.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
