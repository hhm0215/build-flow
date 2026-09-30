[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common.ps1")

$schemas = @(
    "buildflow_auth",
    "buildflow_estimate",
    "buildflow_site",
    "buildflow_purchase",
    "buildflow_tax",
    "buildflow_notification",
    "buildflow_chat"
)
$encoding = New-Object System.Text.UTF8Encoding($false)
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("buildflow-db-backup-test-" + [Guid]::NewGuid().ToString("N"))

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $dumpPath = Join-Path $testRoot "buildflow.sql"
    $dumpLines = @()
    foreach ($schema in $schemas) {
        $dumpLines += "CREATE DATABASE /*!32312 IF NOT EXISTS*/ ``$schema``;"
        $dumpLines += "USE ``$schema``;"
        if ($schema -eq "buildflow_auth") {
            $dumpLines += "CREATE TABLE ``admin_accounts`` (``id`` bigint NOT NULL);"
            $dumpLines += "INSERT INTO ``admin_accounts`` VALUES (1);"
        }
    }
    $dumpLines += "-- Dump completed on 2026-09-29 00:00:00"
    [System.IO.File]::WriteAllText($dumpPath, ($dumpLines -join "`n") + "`n", $encoding)

    $manifest = [ordered]@{
        formatVersion = 1
        createdAtUtc = [DateTime]::UtcNow.ToString("o")
        containsSensitiveData = $true
        mysqlServerVersion = "test"
        schemas = @($schemas | ForEach-Object {
            $tables = if ($_ -eq "buildflow_auth") { @([ordered]@{ name = "admin_accounts"; rowCount = 1 }) } else { @() }
            [ordered]@{ name = $_; tables = $tables; flywayHistoryPresent = $false }
        })
        source = [ordered]@{
            containerId = "test-container"
            composeProject = "test-project"
            composeWorkingDirectory = $testRoot
            imageReference = "mysql:8.0"
            imageId = "sha256:test"
            volumeName = "test_mysql_data"
            volumeDriver = "local"
        }
        dump = [ordered]@{
            file = "buildflow.sql"
            bytes = (Get-Item -LiteralPath $dumpPath).Length
            sha256 = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        administrator = [ordered]@{
            count = 1
            digestAlgorithm = "SHA-256"
            digest = "a" * 64
        }
        security = [ordered]@{ plaintext = $true; broadReadAclWarning = $false }
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $testRoot "manifest.json"),
        ($manifest | ConvertTo-Json -Depth 8),
        $encoding
    )

    $incompleteMarker = Join-Path $testRoot "INCOMPLETE"
    [System.IO.File]::WriteAllText($incompleteMarker, "test", $encoding)
    $incompleteRejected = $false
    try {
        & (Join-Path $PSScriptRoot "verify-backup.ps1") -BackupDirectory $testRoot
    }
    catch {
        $incompleteRejected = $_.Exception.Message -match "Incomplete"
    }
    if (-not $incompleteRejected) { throw "Incomplete backup was not rejected." }
    & (Join-Path $PSScriptRoot "verify-backup.ps1") -BackupDirectory $testRoot -AllowIncomplete
    Remove-Item -LiteralPath $incompleteMarker

    & (Join-Path $PSScriptRoot "verify-backup.ps1") -BackupDirectory $testRoot

    [System.IO.File]::AppendAllText($dumpPath, "corruption", $encoding)
    $corruptionRejected = $false
    try {
        & (Join-Path $PSScriptRoot "verify-backup.ps1") -BackupDirectory $testRoot
    }
    catch {
        $corruptionRejected = $_.Exception.Message -match "size|SHA-256"
    }
    if (-not $corruptionRejected) {
        throw "Corrupted backup was not rejected."
    }

    $spaceDirectory = Join-Path $testRoot "path with space"
    New-Item -ItemType Directory -Path $spaceDirectory | Out-Null
    $echoScript = Join-Path $spaceDirectory "echo arguments.ps1"
    [System.IO.File]::WriteAllText(
        $echoScript,
        'param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Values) [Console]::Out.Write(($Values | ConvertTo-Json -Compress))',
        $encoding
    )
    $expectedArguments = @("value with space\", 'quote"value', "")
    $hostExecutable = (Get-Process -Id $PID).Path
    $argumentResult = Invoke-NativeProcess -FileName $hostExecutable -Arguments (@("-NoProfile", "-File", $echoScript) + $expectedArguments) -WorkingDirectory $testRoot -RejectStandardError
    # Windows PowerShell 5.1 emits a JSON array as one array-valued pipeline
    # object, so cast explicitly to get the individual string arguments.
    $actualArguments = [string[]]($argumentResult.StandardOutput | ConvertFrom-Json)
    if (($actualArguments | ConvertTo-Json -Compress) -cne ($expectedArguments | ConvertTo-Json -Compress)) {
        throw "Native argument quoting did not preserve spaces, quotes, trailing backslashes, and empty values. Expected: $($expectedArguments | ConvertTo-Json -Compress); actual: $($actualArguments | ConvertTo-Json -Compress)"
    }

    $stderrScript = Join-Path $spaceDirectory "stderr.ps1"
    [System.IO.File]::WriteAllText($stderrScript, '[Console]::Error.Write("expected stderr")', $encoding)
    $stderrRejected = $false
    try {
        Invoke-NativeProcess -FileName $hostExecutable -Arguments @("-NoProfile", "-File", $stderrScript) -WorkingDirectory $testRoot -RejectStandardError | Out-Null
    }
    catch {
        $stderrRejected = $_.Exception.Message -match "unexpected stderr"
    }
    if (-not $stderrRejected) { throw "Non-empty stderr was not rejected." }

    Write-Host "[OK] Backup verifier and Windows native argument quoting tests passed."
}
finally {
    $resolvedTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    if ($resolvedTestRoot.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTestRoot) -like "buildflow-db-backup-test-*") {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
