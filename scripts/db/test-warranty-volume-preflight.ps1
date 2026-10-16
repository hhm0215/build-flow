[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'warranty-volume-preflight.ps1')

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$mysql = [pscustomobject]@{
    Config = [pscustomobject]@{
        Image = 'mysql:8.0'
        Labels = [pscustomobject]@{
            'com.docker.compose.project' = 'buildflow-test'
            'com.docker.compose.service' = 'mysql'
            'com.docker.compose.project.working_dir' = $root
        }
    }
    Mounts = @([pscustomobject]@{ Destination = '/var/lib/mysql'; Type = 'volume'; Name = 'buildflow-test_mysql_data' })
}
$db = [pscustomobject]@{
    Name = 'buildflow-test_mysql_data'
    Labels = [pscustomobject]@{ 'com.docker.compose.project' = 'buildflow-test'; 'com.docker.compose.volume' = 'mysql_data' }
}
$warranty = [pscustomobject]@{
    Name = 'buildflow-test_warranty_uploads'
    Driver = 'local'
    Labels = [pscustomobject]@{ 'com.docker.compose.project' = 'buildflow-test'; 'com.docker.compose.volume' = 'warranty_uploads' }
}
$notificationMount = [pscustomobject]@{ type = 'volume'; source = 'warranty_uploads'; target = '/app/uploads/warranties' }

function Assert-Rejected {
    param([Parameter(Mandatory = $true)][scriptblock]$Action, [Parameter(Mandatory = $true)][string]$Pattern)
    $rejected = $false
    try { & $Action | Out-Null }
    catch { $rejected = $_.Exception.Message -match $Pattern }
    if (-not $rejected) { throw "Expected preflight rejection: $Pattern" }
}

$result = Assert-WarrantyVolumePreflight -MySqlContainer $mysql -MySqlVolume $db -WarrantyVolume $warranty -ExpectedWorkingDirectory $root -NotificationVolumeMounts @($notificationMount) -RunningComposeServices @('mysql', 'gateway-server') -RunningWarrantyContainerIds @()
if ($result.volumeName -cne 'buildflow-test_warranty_uploads') { throw 'Wrong volume identity.' }
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty $root @($notificationMount) @('mysql') @('running-id') } 'still mounted'
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty $root @($notificationMount) @('mysql', 'notification-service') @() } 'still running'
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty (Join-Path $root 'other') @($notificationMount) @('mysql') @() } 'working directory'
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty $root @([pscustomobject]@{ type = 'bind'; source = 'warranty_uploads'; target = '/app/uploads/warranties' }) @('mysql') @() } 'must map warranty_uploads'
$warranty.Labels.'com.docker.compose.project' = 'other'
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty $root @($notificationMount) @('mysql') @() } 'volume identity'
$warranty.Labels.'com.docker.compose.project' = 'buildflow-test'
$warranty.Driver = 'remote'
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty $root @($notificationMount) @('mysql') @() } 'volume identity'
$warranty.Driver = 'local'
$warranty.Name = $db.Name
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty $root @($notificationMount) @('mysql') @() } 'volume identity'
$warranty.Name = 'buildflow-test_warranty_uploads'
$mysql.Config.Labels.'com.docker.compose.service' = 'other'
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty $root @($notificationMount) @('mysql') @() } 'mysql service'
$mysql.Config.Labels.'com.docker.compose.service' = 'mysql'
$db.Labels.'com.docker.compose.volume' = 'wrong'
Assert-Rejected { Assert-WarrantyVolumePreflight $mysql $db $warranty $root @($notificationMount) @('mysql') @() } 'MySQL volume identity'
Write-Host '[OK] Warranty volume preflight synthetic tests passed.'
