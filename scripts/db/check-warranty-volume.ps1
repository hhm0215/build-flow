[CmdletBinding()]
param(
    [switch]$PassThru,
    [switch]$SyntheticTestSource,
    [string]$SyntheticProject,
    [string]$SyntheticNonce
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'warranty-volume-preflight.ps1')

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ($SyntheticTestSource) {
    if ($SyntheticProject -cnotmatch '^buildflowsnap[a-f0-9]{12}$' -or $SyntheticNonce -cnotmatch '^[a-f0-9]{32}$' -or
        $env:SNAPSHOT_TEST_NONCE -cne $SyntheticNonce) { throw 'Synthetic source project or nonce is invalid.' }
    $composeArgs = @('compose', '--project-directory', $repoRoot, '--project-name', $SyntheticProject, '-f', (Join-Path $PSScriptRoot 'snapshot-test.compose.yml'))
} else {
    if ($PSBoundParameters.ContainsKey('SyntheticProject') -or $PSBoundParameters.ContainsKey('SyntheticNonce')) {
        throw 'Synthetic project and nonce require -SyntheticTestSource.'
    }
    $composeArgs = @('compose', '-f', (Join-Path $repoRoot 'docker-compose.yml'), '-f', (Join-Path $repoRoot 'docker-compose.app.yml'))
}

function Invoke-DockerLines {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $result = Invoke-NativeProcess -FileName 'docker' -Arguments $Arguments -WorkingDirectory $repoRoot -RejectStandardError
    return @(Get-OutputLines -Text $result.StandardOutput)
}

function Get-SingleDockerObject {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $lines = @(Invoke-DockerLines -Arguments $Arguments)
    if ($lines.Count -eq 0) { throw 'Docker inspect returned no object.' }
    $objects = @((($lines -join "`n") | ConvertFrom-Json))
    if ($objects.Count -ne 1) { throw 'Docker inspect did not return exactly one object.' }
    return $objects[0]
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required.' }
$mysqlIds = @(Invoke-DockerLines -Arguments ($composeArgs + @('ps', '-q', 'mysql')))
if ($mysqlIds.Count -ne 1) { throw 'Expected exactly one running Compose MySQL container.' }
$mysqlContainer = Get-SingleDockerObject -Arguments @('inspect', $mysqlIds[0])
$dbMounts = @($mysqlContainer.Mounts | Where-Object { $_.Destination -ceq '/var/lib/mysql' })
if ($dbMounts.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$dbMounts[0].Name)) {
    throw 'Could not identify the MySQL named volume.'
}
$dbVolume = Get-SingleDockerObject -Arguments @('volume', 'inspect', [string]$dbMounts[0].Name)
$project = Get-RequiredDockerLabel -Labels $mysqlContainer.Config.Labels -Name 'com.docker.compose.project'
if ($SyntheticTestSource -and ($project -cne $SyntheticProject -or
    (Get-RequiredDockerLabel -Labels $mysqlContainer.Config.Labels -Name 'com.buildflow.snapshot-test') -cne $SyntheticNonce)) {
    throw 'Synthetic MySQL project or nonce identity mismatch.'
}
$configJson = (Invoke-NativeProcess -FileName 'docker' -Arguments ($composeArgs + @('config', '--format', 'json')) -WorkingDirectory $repoRoot -RejectStandardError).StandardOutput
$config = $configJson | ConvertFrom-Json
if ($null -eq $config.services -or $null -eq $config.services.'notification-service') {
    throw 'Compose notification-service configuration is missing.'
}
$warrantyNames = @(Invoke-DockerLines -Arguments @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project", '--filter', 'label=com.docker.compose.volume=warranty_uploads'))
if ($warrantyNames.Count -ne 1) { throw 'Expected exactly one warranty_uploads volume in this Compose project.' }
$warrantyVolume = Get-SingleDockerObject -Arguments @('volume', 'inspect', $warrantyNames[0])
$runningUsers = @(Invoke-DockerLines -Arguments @('ps', '-q', '--filter', "volume=$($warrantyVolume.Name)"))
$runningServices = @(Invoke-DockerLines -Arguments ($composeArgs + @('ps', '--services', '--status', 'running')))
$identity = Assert-WarrantyVolumePreflight -MySqlContainer $mysqlContainer -MySqlVolume $dbVolume -WarrantyVolume $warrantyVolume -ExpectedWorkingDirectory $repoRoot -NotificationVolumeMounts @($config.services.'notification-service'.volumes) -RunningComposeServices $runningServices -RunningWarrantyContainerIds $runningUsers
if ($SyntheticTestSource -and ((Get-RequiredDockerLabel -Labels $dbVolume.Labels -Name 'com.buildflow.snapshot-test') -cne $SyntheticNonce -or
    (Get-RequiredDockerLabel -Labels $warrantyVolume.Labels -Name 'com.buildflow.snapshot-test') -cne $SyntheticNonce)) {
    throw 'Synthetic source volume nonce identity mismatch.'
}
if ($PassThru) {
    Write-Output $identity
}
else {
    Write-Host "[OK] Warranty volume identity and no currently running Compose writer or volume mount: $($identity.volumeName)"
    Write-Warning 'Preflight only: external writers are unverified; no upload files were copied, no DB references were checked, and no restorable v2 backup was produced.'
}
