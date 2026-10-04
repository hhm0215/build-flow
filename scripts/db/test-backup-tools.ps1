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

function Invoke-WarrantyVerifier {
    $package = Get-Content -LiteralPath (Join-Path $testRoot "manifest.json") -Raw | ConvertFrom-Json
    & (Join-Path $PSScriptRoot "verify-warranty-files.ps1") -BackupDirectory $testRoot -Uploads $package.uploads -SqlSource $package.source
}

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

    [System.IO.File]::WriteAllText($dumpPath, ($dumpLines -join "`n") + "`n", $encoding)
    $warrantyDirectory = Join-Path $testRoot "uploads\warranties"
    New-Item -ItemType Directory -Path $warrantyDirectory -Force | Out-Null
    $warrantyName = "0f86568e-8ac8-4226-b224-c0f9a7f72f3a.pdf"
    $warrantyPath = Join-Path $warrantyDirectory $warrantyName
    [System.IO.File]::WriteAllText($warrantyPath, "synthetic-warranty", $encoding)
    $manifest.formatVersion = 2
    $manifest.uploads = [ordered]@{
        directory = "uploads/warranties"
        mountPath = "/app/uploads/warranties"
        source = [ordered]@{ composeProject = "test-project"; volumeName = "test_warranty_uploads"; volumeDriver = "local" }
        files = @([ordered]@{
            relativePath = $warrantyName
            bytes = (Get-Item -LiteralPath $warrantyPath).Length
            sha256 = (Get-FileHash -LiteralPath $warrantyPath -Algorithm SHA256).Hash.ToLowerInvariant()
        })
        references = @([ordered]@{ warrantyId = 1; relativePath = $warrantyName })
        referenceCount = 1
    }
    Write-JsonUtf8 -Path (Join-Path $testRoot "manifest.json") -Value $manifest
    $v2Rejected = $false
    try { & (Join-Path $PSScriptRoot "verify-backup.ps1") -BackupDirectory $testRoot }
    catch { $v2Rejected = $_.Exception.Message -match "restored-DB warranty reference validation" }
    if (-not $v2Rejected) { throw "Unbound v2 package was incorrectly accepted as a complete backup." }
    Invoke-WarrantyVerifier

    [System.IO.File]::WriteAllText($warrantyPath, "synthetic-warrantx", $encoding)
    $warrantyCorruptionRejected = $false
    try { Invoke-WarrantyVerifier }
    catch { $warrantyCorruptionRejected = $_.Exception.Message -match "SHA-256|size" }
    if (-not $warrantyCorruptionRejected) { throw "Corrupted warranty file was not rejected." }
    [System.IO.File]::WriteAllText($warrantyPath, "synthetic-warranty", $encoding)

    $manifest.uploads.files[0].relativePath = "..\\outside.pdf"
    Write-JsonUtf8 -Path (Join-Path $testRoot "manifest.json") -Value $manifest
    $traversalRejected = $false
    try { Invoke-WarrantyVerifier }
    catch { $traversalRejected = $_.Exception.Message -match "Unsafe warranty" }
    if (-not $traversalRejected) { throw "Warranty path traversal was not rejected." }
    $manifest.uploads.files[0].relativePath = $warrantyName
    Write-JsonUtf8 -Path (Join-Path $testRoot "manifest.json") -Value $manifest

    $manifest.uploads.source.composeProject = "other-project"
    Write-JsonUtf8 -Path (Join-Path $testRoot "manifest.json") -Value $manifest
    $wrongSourceRejected = $false
    try { Invoke-WarrantyVerifier }
    catch { $wrongSourceRejected = $_.Exception.Message -match "Warranty source" }
    if (-not $wrongSourceRejected) { throw "Mismatched warranty source project was not rejected." }
    $manifest.uploads.source.composeProject = "test-project"
    Write-JsonUtf8 -Path (Join-Path $testRoot "manifest.json") -Value $manifest

    $manifest.uploads.references[0].relativePath = "missing.pdf"
    Write-JsonUtf8 -Path (Join-Path $testRoot "manifest.json") -Value $manifest
    $missingReferenceRejected = $false
    try { Invoke-WarrantyVerifier }
    catch { $missingReferenceRejected = $_.Exception.Message -match "no packaged file" }
    if (-not $missingReferenceRejected) { throw "Missing warranty reference was not rejected." }
    $manifest.uploads.references[0].relativePath = $warrantyName
    Write-JsonUtf8 -Path (Join-Path $testRoot "manifest.json") -Value $manifest

    $extraPath = Join-Path $warrantyDirectory "orphan.pdf"
    [System.IO.File]::WriteAllText($extraPath, "unlisted", $encoding)
    $extraRejected = $false
    try { Invoke-WarrantyVerifier }
    catch { $extraRejected = $_.Exception.Message -match "count|Unlisted" }
    if (-not $extraRejected) { throw "Unlisted warranty file was not rejected." }
    Remove-Item -LiteralPath $extraPath

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

    Write-Host "[OK] SQL verifier, warranty file-only diagnostics, v2 fail-closed gate, and Windows argument quoting tests passed."
}
finally {
    $resolvedTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    if ($resolvedTestRoot.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTestRoot) -like "buildflow-db-backup-test-*") {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
