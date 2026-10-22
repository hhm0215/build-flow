[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$nonce = [Guid]::NewGuid().ToString('N')
$project = 'buildflowsnap' + $nonce.Substring(0, 12)
$composeArgs = @('compose', '--project-directory', $repoRoot, '--project-name', $project, '-f', (Join-Path $PSScriptRoot 'snapshot-test.compose.yml'))
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-v2-snapshot-docker-' + $nonce)
$pdfName = '0f86568e-8ac8-4226-b224-c0f9a7f72f3a.pdf'
$orphanName = '420e6e50-6b73-4aa8-b691-209e870e37be.pdf'
$environmentNames = @('SNAPSHOT_TEST_DB_PASSWORD', 'SNAPSHOT_TEST_NONCE')
$previous = @{}
$started = $false
$passed = $false

function Invoke-TestDocker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return (Invoke-NativeProcess -FileName 'docker' -Arguments $Arguments -WorkingDirectory $repoRoot -RejectStandardError:$false).StandardOutput
}

function Get-TestLines {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return @(Get-OutputLines -Text (Invoke-TestDocker -Arguments $Arguments))
}

function Get-TestLabel {
    param([Parameter(Mandatory = $true)][object]$Labels, [Parameter(Mandatory = $true)][string]$Name)
    $value = $Labels.PSObject.Properties[$Name]
    if ($null -eq $value -or [string]::IsNullOrWhiteSpace([string]$value.Value)) { throw "Missing synthetic resource label: $Name" }
    return [string]$value.Value
}

function Assert-TestVolume {
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][string]$Role)
    $volume = @((Invoke-TestDocker -Arguments @('volume', 'inspect', $Name) | ConvertFrom-Json)) | Select-Object -First 1
    if ([string]$volume.Name -cne $Name -or [string]$volume.Driver -cne 'local' -or
        ($null -ne $volume.Options -and @($volume.Options.PSObject.Properties).Count -ne 0) -or
        (Get-TestLabel -Labels $volume.Labels -Name 'com.docker.compose.project') -cne $project -or
        (Get-TestLabel -Labels $volume.Labels -Name 'com.docker.compose.volume') -cne $Role -or
        (Get-TestLabel -Labels $volume.Labels -Name 'com.buildflow.snapshot-test') -cne $nonce) {
        throw 'Synthetic volume identity failed.'
    }
}

function Assert-TestCleanupIdentity {
    $seenServices = @()
    foreach ($id in @(Get-TestLines -Arguments @('ps', '-aq', '--filter', "label=com.docker.compose.project=$project"))) {
        $item = @((Invoke-TestDocker -Arguments @('inspect', $id) | ConvertFrom-Json)) | Select-Object -First 1
        $service = Get-TestLabel -Labels $item.Config.Labels -Name 'com.docker.compose.service'
        if ((Get-TestLabel -Labels $item.Config.Labels -Name 'com.docker.compose.project') -cne $project -or
            (Get-TestLabel -Labels $item.Config.Labels -Name 'com.buildflow.snapshot-test') -cne $nonce -or
            $service -notin @('mysql', 'notification-service') -or $service -in $seenServices) {
            throw 'Unexpected synthetic container; refusing cleanup.'
        }
        $seenServices += $service
    }
    if ($seenServices.Count -ne 2) { throw 'Synthetic witness containers are missing; refusing cleanup.' }
    $seenVolumes = @()
    foreach ($name in @(Get-TestLines -Arguments @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project"))) {
        $role = $name.Substring($project.Length + 1)
        if ($name -cne ($project + '_' + $role) -or $role -notin @('mysql_data', 'warranty_uploads') -or $role -in $seenVolumes) {
            throw 'Unexpected synthetic volume; refusing cleanup.'
        }
        Assert-TestVolume -Name $name -Role $role
        $seenVolumes += $role
    }
    if ($seenVolumes.Count -ne 2) { throw 'Synthetic volumes are missing; refusing cleanup.' }
    $networks = @(Get-TestLines -Arguments @('network', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project"))
    if ($networks.Count -ne 1) { throw 'Synthetic network identity is ambiguous.' }
    $network = @((Invoke-TestDocker -Arguments @('network', 'inspect', $networks[0]) | ConvertFrom-Json)) | Select-Object -First 1
    if ((Get-TestLabel -Labels $network.Labels -Name 'com.docker.compose.project') -cne $project -or
        (Get-TestLabel -Labels $network.Labels -Name 'com.buildflow.snapshot-test') -cne $nonce) {
        throw 'Synthetic network nonce mismatch.'
    }
}

function Set-PrivateTestDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        & chmod 700 -- $Path
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare private Unix synthetic snapshot directory.' }
        return
    }
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sidText in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')) {
        $sid = New-Object Security.Principal.SecurityIdentifier($sidText)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

foreach ($name in $environmentNames) {
    $item = Get-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    $previous[$name] = if ($null -eq $item) { $null } else { [string]$item.Value }
}
$env:SNAPSHOT_TEST_DB_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$env:SNAPSHOT_TEST_NONCE = $nonce

try {
    $existing = @(Get-TestLines -Arguments @('ps', '-aq', '--filter', "label=com.docker.compose.project=$project"))
    $existingVolumes = @(Get-TestLines -Arguments @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project"))
    $existingNetworks = @(Get-TestLines -Arguments @('network', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project"))
    if ($existing.Count -ne 0 -or $existingVolumes.Count -ne 0 -or $existingNetworks.Count -ne 0) {
        throw 'Synthetic project resources already exist.'
    }
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    Set-PrivateTestDirectory -Path $tempRoot

    $started = $true
    [void](Invoke-TestDocker -Arguments ($composeArgs + @('up', '-d', '--wait', 'mysql')))
    [void](Invoke-TestDocker -Arguments ($composeArgs + @('--profile', 'warranty-witness', 'up', '-d', 'notification-service')))
    Assert-TestVolume -Name ($project + '_mysql_data') -Role 'mysql_data'
    Assert-TestVolume -Name ($project + '_warranty_uploads') -Role 'warranty_uploads'

    $schemas = @('buildflow_auth', 'buildflow_estimate', 'buildflow_site', 'buildflow_purchase', 'buildflow_tax', 'buildflow_notification', 'buildflow_chat')
    $sql = New-Object 'System.Collections.Generic.List[string]'
    foreach ($schema in $schemas) { $sql.Add("CREATE DATABASE ``$schema``;") }
    $sql.Add('CREATE TABLE buildflow_auth.admin_accounts (id BIGINT NOT NULL PRIMARY KEY, login_id VARCHAR(50) NOT NULL UNIQUE, password VARCHAR(255) NOT NULL, name VARCHAR(50) NOT NULL, created_at DATETIME(6) NOT NULL, CONSTRAINT chk_single_admin_id CHECK (id = 1));')
    $sql.Add("INSERT INTO buildflow_auth.admin_accounts VALUES (1, 'qaadmin', 'synthetic-hash', 'QA', '2026-10-16 00:00:00.000000');")
    $sql.Add('CREATE TABLE buildflow_notification.defect_warranties (id BIGINT NOT NULL PRIMARY KEY, file_path VARCHAR(1024) NULL);')
    $sql.Add("INSERT INTO buildflow_notification.defect_warranties VALUES (1, '/app/uploads/warranties/$pdfName'), (2, NULL);")
    [void](Invoke-TestDocker -Arguments ($composeArgs + @('exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --execute="$1"', 'synthetic-seed', ($sql -join ' '))))
    [void](Invoke-TestDocker -Arguments @('run', '--rm', '--pull=never', '--network', 'none', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--mount', "type=volume,source=$($project)_warranty_uploads,target=/data", '--entrypoint', 'sh', 'mysql:8.0', '-c', "printf 'synthetic-referenced-pdf' > /data/$pdfName; printf 'synthetic-orphan-pdf' > /data/$orphanName"))

    & (Join-Path $PSScriptRoot 'backup.ps1') -OutputRoot $tempRoot -IncludeWarrantyUploads -SyntheticTestSource -SyntheticProject $project -SyntheticNonce $nonce
    $packages = @(Get-ChildItem -LiteralPath $tempRoot -Directory -Force | Where-Object { $_.Name -like '.partial-*' })
    if ($packages.Count -ne 1 -or -not (Test-Path -LiteralPath (Join-Path $packages[0].FullName 'INCOMPLETE') -PathType Leaf)) {
        throw 'Synthetic snapshot did not retain exactly one INCOMPLETE diagnostic package.'
    }
    & (Join-Path $PSScriptRoot 'restore-test.ps1') -BackupDirectory $packages[0].FullName -TestIncompleteV2
    $passed = $true
    Write-Host '[OK] Synthetic Docker source snapshot and isolated DB+file restore passed; real source and external recovery remain UNVERIFIED.'
}
finally {
    if ($started) {
        try {
            Assert-TestCleanupIdentity
            [void](Invoke-TestDocker -Arguments ($composeArgs + @('--profile', 'warranty-witness', 'down', '--volumes')))
            $remaining = @(Get-TestLines -Arguments @('ps', '-aq', '--filter', "label=com.docker.compose.project=$project")) +
                @(Get-TestLines -Arguments @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project")) +
                @(Get-TestLines -Arguments @('network', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project"))
            if ($remaining.Count -ne 0) { throw 'Synthetic Docker cleanup left owned resources.' }
            Write-Host '[OK] Synthetic source containers, volumes, and network removed.'
        }
        catch { Write-Warning "Synthetic project $project retained for inspection: $($_.Exception.Message)"; $passed = $false }
    }
    foreach ($name in $environmentNames) {
        if ($null -eq $previous[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath "Env:$name" -Value $previous[$name] }
    }
    $resolved = [IO.Path]::GetFullPath($tempRoot)
    $prefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($passed -and $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved) -ceq ('buildflow-v2-snapshot-docker-' + $nonce)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    } elseif (Test-Path -LiteralPath $resolved) { Write-Warning "Synthetic package retained for inspection: $resolved" }
}
if (-not $passed) { throw 'Synthetic snapshot test cleanup was incomplete; inspect the retained project or package.' }
