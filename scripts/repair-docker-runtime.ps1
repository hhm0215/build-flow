[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = "Medium")]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "This recovery script only supports Docker Desktop on Windows."
}

$dockerProcessNames = @("Docker Desktop", "com.docker.backend")
$runningDockerProcesses = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessName -in $dockerProcessNames
})
if ($runningDockerProcesses.Count -gt 0) {
    $names = ($runningDockerProcesses.ProcessName | Sort-Object -Unique) -join ", "
    throw "Docker Desktop is still running ($names). Quit Docker Desktop completely before quarantining runtime sockets."
}

$rawLocalAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
if ([string]::IsNullOrWhiteSpace($rawLocalAppData)) {
    throw "Could not resolve the current user's LocalAppData directory."
}
$localAppData = [System.IO.Path]::GetFullPath($rawLocalAppData).TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar
)

function Assert-LocalAppDataPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $prefix = $localAppData + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to operate outside LocalAppData: $fullPath"
    }
    return $fullPath
}

$timestamp = [DateTime]::UtcNow.ToString("yyyyMMdd-HHmmss")
$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$targets = @(
    [ordered]@{
        Source = Join-Path $localAppData "Docker\run"
        Destination = Join-Path $localAppData "Docker\run.quarantine-$timestamp-$suffix"
    },
    [ordered]@{
        Source = Join-Path $localAppData "docker-secrets-engine"
        Destination = Join-Path $localAppData "docker-secrets-engine.quarantine-$timestamp-$suffix"
    }
)

$preparedTargets = @()
foreach ($target in $targets) {
    $source = Assert-LocalAppDataPath -Path $target.Source
    $destination = Assert-LocalAppDataPath -Path $target.Destination
    if (-not (Test-Path -LiteralPath $source)) {
        Write-Host "[SKIP] Runtime path does not exist: $source"
        continue
    }
    if (Test-Path -LiteralPath $destination) {
        throw "Quarantine destination already exists: $destination"
    }
    $preparedTargets += [pscustomobject]@{ Source = $source; Destination = $destination }
}

$moved = 0
foreach ($target in $preparedTargets) {
    $runningDockerProcesses = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -in $dockerProcessNames
    })
    if ($runningDockerProcesses.Count -gt 0) {
        throw "Docker Desktop started again during validation. Quit it completely before retrying."
    }

    if ($PSCmdlet.ShouldProcess($target.Source, "Move Docker runtime files to $($target.Destination)")) {
        Move-Item -LiteralPath $target.Source -Destination $target.Destination
        Write-Host "[MOVED] $($target.Source)"
        Write-Host "        -> $($target.Destination)"
        $moved++
    }
}

if ($moved -eq 0) {
    Write-Host "No Docker runtime directory was moved. In -WhatIf mode this is expected."
}
else {
    Write-Host "Quarantined $moved runtime director$(if ($moved -eq 1) { 'y' } else { 'ies' })."
    Write-Host "Start Docker Desktop, then run 'docker info'. Images, containers, volumes, and WSL data were not touched."
}
