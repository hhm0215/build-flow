Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'flyway-ddl-compare.ps1')

# Synthetic fixtures only (no production data). Three services:
#   auth = identical to V1, tax = one column type differs, site = one table missing in prod.
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$usersV1 = @"
CREATE TABLE ``users`` (
  ``id`` bigint NOT NULL AUTO_INCREMENT,
  ``username`` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (``id``),
  UNIQUE KEY ``uk_users_username`` (``username``)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"@
$invoicesV1 = @"
CREATE TABLE ``tax_invoices`` (
  ``id`` bigint NOT NULL AUTO_INCREMENT,
  ``supply_amount`` decimal(15,0) NOT NULL,
  ``memo`` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT 'a;b,c',
  PRIMARY KEY (``id``)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"@
$sitesV1 = @"
CREATE TABLE ``sites`` (
  ``id`` bigint NOT NULL AUTO_INCREMENT,
  ``name`` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (``id``)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"@
$clientsV1 = @"
CREATE TABLE ``clients`` (
  ``id`` bigint NOT NULL AUTO_INCREMENT,
  PRIMARY KEY (``id``)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"@

# Production dump in `buildflow-release schema` format: AUTO_INCREMENT=N, redundant charset notation,
# flyway_schema_history and a standalone ';' line must not count as differences.
$prodDump = @"
-- buildflow-release schema HEAD 0000000000000000000000000000000000000000 2026-10-23T00:00:00Z
-- schema: buildflow_auth
-- table: users
CREATE TABLE ``users`` (
  ``id`` bigint NOT NULL AUTO_INCREMENT,
  ``username`` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (``id``),
  UNIQUE KEY ``uk_users_username`` (``username``)
) ENGINE=InnoDB AUTO_INCREMENT=42 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;
-- table: flyway_schema_history
CREATE TABLE ``flyway_schema_history`` (
  ``installed_rank`` int NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;
-- schema: buildflow_tax
-- table: tax_invoices
CREATE TABLE ``tax_invoices`` (
  ``id`` bigint NOT NULL AUTO_INCREMENT,
  ``supply_amount`` decimal(12,0) NOT NULL,
  ``memo`` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT 'a;b,c',
  PRIMARY KEY (``id``)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;
-- schema: buildflow_site
-- table: sites
CREATE TABLE ``sites`` (
  ``id`` bigint NOT NULL AUTO_INCREMENT,
  ``name`` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (``id``)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
;
"@

# Normalization and batch-escape helpers.
$escaped = 'CREATE TABLE `t` (\n  `a` varchar(5) DEFAULT ''x\\y'',\n  `b` int\n) ENGINE=InnoDB AUTO_INCREMENT=9'
$lines = ConvertTo-NormalizedDdlLines -Ddl (ConvertFrom-MySqlBatchEscape -Text $escaped)
Assert-True (($lines -join '|') -ceq 'CREATE TABLE `t` (|`a` varchar(5) DEFAULT ''x\y''|`b` int|) ENGINE=InnoDB') "Batch escape/normalization failed: $($lines -join '|')"
Assert-True ((Get-CharsetNotationCount -Ddl $prodDump) -eq 1) 'Charset notation count differs.'

$prod = ConvertFrom-DdlDump -Text $prodDump
Assert-True (@($prod.Keys | Sort-Object) -join ',' -ceq 'buildflow_auth,buildflow_site,buildflow_tax') 'Schema markers not parsed.'
Assert-True (-not $prod['buildflow_auth'].Contains('flyway_schema_history')) 'flyway_schema_history must be ignored.'

$v1 = [ordered]@{
    auth = (ConvertFrom-DdlDump -Text $usersV1)['']
    tax  = (ConvertFrom-DdlDump -Text $invoicesV1)['']
    site = (ConvertFrom-DdlDump -Text ($clientsV1 + "`n" + $sitesV1))['']
}
$rows = Compare-ProdDdl -Prod $prod -V1ByService $v1

Assert-True ($rows['auth'].Differences.Count -eq 0 -and $rows['auth'].SchemaPresent) "auth should match: $($rows['auth'].Differences -join ' / ')"

$tax = $rows['tax']
Assert-True ($tax.Differences.Count -eq 2 -and $tax.LineDiffTables.Count -eq 1 -and $tax.OnlyLeftTables.Count -eq 0 -and $tax.OnlyRightTables.Count -eq 0) "tax diff shape wrong: $($tax.Differences -join ' / ')"
Assert-True ($tax.Differences -ccontains 'tax_invoices prod: `supply_amount` decimal(12,0) NOT NULL') 'tax prod line missing.'
Assert-True ($tax.Differences -ccontains 'tax_invoices V1: `supply_amount` decimal(15,0) NOT NULL') 'tax V1 line missing.'

$site = $rows['site']
Assert-True ($site.Differences.Count -eq 1 -and $site.Differences[0] -ceq 'table only in V1: clients' -and $site.LeftTables -eq 1 -and $site.RightTables -eq 2) "site diff wrong: $($site.Differences -join ' / ')"

# Column order only: reported separately, not as a difference.
$reordered = ConvertFrom-DdlDump -Text ($usersV1 -replace '(?m)^(  `id`.*)\r?\n(  `username`.*)$', "`$2`n`$1")
$order = Compare-DdlMaps -Left $reordered[''] -Right $v1['auth']
Assert-True ($order.Differences.Count -eq 0 -and ($order.OrderOnlyTables -join ',') -ceq 'users') 'Order-only table not detected.'

# A schema marker without tables is present but empty: every V1 table is reported as missing.
$empty = Compare-ProdDdl -Prod (ConvertFrom-DdlDump -Text "-- schema: buildflow_chat`n") -V1ByService ([ordered]@{ chat = $v1['auth'] })
Assert-True ($empty['chat'].SchemaPresent -and ($empty['chat'].Differences -join ',') -ceq 'table only in V1: users') 'Empty schema not reported as missing tables.'

# Missing schema in the dump.
$missing = Compare-ProdDdl -Prod @{} -V1ByService ([ordered]@{ chat = $v1['auth'] })
Assert-True (-not $missing['chat'].SchemaPresent -and $missing['chat'].Differences.Count -eq 1) 'Missing schema not reported.'

$report = Format-ProdDdlReport -Rows $rows
Assert-True (@($report | Where-Object { $_ -match '^auth .*\| MATCH$' }).Count -eq 1 -and @($report | Where-Object { $_ -match '^tax .*\| DIFF$' }).Count -eq 1) "Report table wrong:`n$($report -join "`n")"

$rejected = $false
try { [void](ConvertFrom-DdlDump -Text "-- schema: x`nCREATE TABLE ``a`` (`n  ``id`` int") } catch { $rejected = $true }
Assert-True $rejected 'Unterminated statement must be rejected.'

# End to end through the rehearsal entry point in offline mode (-V1DdlDirectory skips Docker).
$root = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-ddl-compare-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
try {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $root 'auth-v1.sql'), $usersV1, $utf8)
    [IO.File]::WriteAllText((Join-Path $root 'tax-v1.sql'), $invoicesV1, $utf8)
    # UTF-16 with BOM, as a Windows PowerShell 5.1 `>` redirect would save it.
    [IO.File]::WriteAllText((Join-Path $root 'prod-ddl.sql'), $prodDump, [Text.Encoding]::Unicode)
    $host_ = (Get-Process -Id $PID).Path
    $script = Join-Path $PSScriptRoot 'flyway-baseline-rehearsal.ps1'
    $out = & $host_ -NoProfile -ExecutionPolicy Bypass -File $script -Services auth -ProdDdlPath (Join-Path $root 'prod-ddl.sql') -V1DdlDirectory $root 2>&1
    Assert-True ($LASTEXITCODE -eq 0 -and ($out -join "`n") -match 'PROD DDL MATCHES V1') "Offline match run failed ($LASTEXITCODE): $($out -join "`n")"
    $out = & $host_ -NoProfile -ExecutionPolicy Bypass -File $script -Services tax -ProdDdlPath (Join-Path $root 'prod-ddl.sql') -V1DdlDirectory $root 2>&1
    Assert-True ($LASTEXITCODE -eq 1 -and ($out -join "`n") -match 'PROD DDL DIFF: tax') "Offline diff run should fail on tax ($LASTEXITCODE): $($out -join "`n")"
}
finally {
    Remove-Item -LiteralPath $root -Recurse -Force
}

Write-Host 'flyway DDL compare tests passed.'
