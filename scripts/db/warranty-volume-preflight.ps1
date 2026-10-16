Set-StrictMode -Version Latest

function Get-RequiredDockerLabel {
    param([Parameter(Mandatory = $true)][object]$Labels, [Parameter(Mandatory = $true)][string]$Name)

    $property = $Labels.PSObject.Properties[$Name]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw "Required Docker label is missing: $Name"
    }
    return [string]$property.Value
}

function Assert-WarrantyVolumePreflight {
    param(
        [Parameter(Mandatory = $true)][object]$MySqlContainer,
        [Parameter(Mandatory = $true)][object]$MySqlVolume,
        [Parameter(Mandatory = $true)][object]$WarrantyVolume,
        [Parameter(Mandatory = $true)][string]$ExpectedWorkingDirectory,
        [AllowEmptyCollection()][Parameter(Mandatory = $true)][object[]]$NotificationVolumeMounts,
        [AllowEmptyCollection()][Parameter(Mandatory = $true)][string[]]$RunningComposeServices,
        [AllowEmptyCollection()][Parameter(Mandatory = $true)][string[]]$RunningWarrantyContainerIds
    )

    $project = Get-RequiredDockerLabel -Labels $MySqlContainer.Config.Labels -Name 'com.docker.compose.project'
    if ((Get-RequiredDockerLabel -Labels $MySqlContainer.Config.Labels -Name 'com.docker.compose.service') -cne 'mysql') {
        throw 'Selected container is not the Compose mysql service.'
    }
    $workingDirectory = Get-RequiredDockerLabel -Labels $MySqlContainer.Config.Labels -Name 'com.docker.compose.project.working_dir'
    $expected = [System.IO.Path]::GetFullPath($ExpectedWorkingDirectory).TrimEnd('\', '/')
    $actual = [System.IO.Path]::GetFullPath($workingDirectory).TrimEnd('\', '/')
    if (-not $actual.Equals($expected, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'MySQL Compose working directory does not match this repository.'
    }
    if ([string]$MySqlContainer.Config.Image -cne 'mysql:8.0') {
        throw 'Unexpected MySQL image reference.'
    }
    $dbMounts = @($MySqlContainer.Mounts | Where-Object { $_.Destination -ceq '/var/lib/mysql' })
    if ($dbMounts.Count -ne 1 -or $dbMounts[0].Type -cne 'volume' -or [string]::IsNullOrWhiteSpace([string]$dbMounts[0].Name)) {
        throw 'MySQL data mount must be exactly one named volume.'
    }
    if ([string]$MySqlVolume.Name -cne [string]$dbMounts[0].Name -or
        (Get-RequiredDockerLabel -Labels $MySqlVolume.Labels -Name 'com.docker.compose.project') -cne $project -or
        (Get-RequiredDockerLabel -Labels $MySqlVolume.Labels -Name 'com.docker.compose.volume') -cne 'mysql_data') {
        throw 'MySQL volume identity does not match this Compose project.'
    }
    if ([string]$WarrantyVolume.Name -ceq [string]$MySqlVolume.Name -or
        [string]$WarrantyVolume.Driver -cne 'local' -or
        (Get-RequiredDockerLabel -Labels $WarrantyVolume.Labels -Name 'com.docker.compose.project') -cne $project -or
        (Get-RequiredDockerLabel -Labels $WarrantyVolume.Labels -Name 'com.docker.compose.volume') -cne 'warranty_uploads') {
        throw 'Warranty upload volume identity does not match this Compose project.'
    }
    $configuredMounts = @($NotificationVolumeMounts | Where-Object { $_.target -ceq '/app/uploads/warranties' })
    if ($configuredMounts.Count -ne 1 -or $configuredMounts[0].type -cne 'volume' -or $configuredMounts[0].source -cne 'warranty_uploads') {
        throw 'Compose notification-service must map warranty_uploads to its expected upload path.'
    }
    if ($RunningWarrantyContainerIds.Count -ne 0) {
        throw 'The warranty upload volume is still mounted by a running container.'
    }
    $writers = @('auth-service', 'estimate-service', 'site-service', 'purchase-service', 'tax-service', 'notification-service', 'chat-service')
    if (@($RunningComposeServices | Where-Object { $_ -in $writers }).Count -ne 0) {
        throw 'One or more BuildFlow database-writing services are still running.'
    }

    return [ordered]@{
        composeProject = $project
        composeWorkingDirectory = $actual
        volumeName = [string]$WarrantyVolume.Name
        volumeDriver = [string]$WarrantyVolume.Driver
        mountPath = '/app/uploads/warranties'
    }
}
