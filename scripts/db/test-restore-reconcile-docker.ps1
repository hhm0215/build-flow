[CmdletBinding()]
param()

# Synthetic regression for restore-reconcile-check.ps1 on an isolated MySQL 8.
# Uses a random Compose project (bfrecon<random>) with tmpfs data, no host ports and a nonce label
# (shared flyway-baseline-rehearsal.compose.yml). Never touches the `buildflow` project, named volumes,
# `down -v` or force flags. Schemas come from the services' Flyway V1/V2 files.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$nonce = [Guid]::NewGuid().ToString('N')
$project = 'bfrecon' + (Get-Random -Minimum 10000 -Maximum 99999)
$labelKey = 'com.buildflow.flyway-rehearsal'
$composeArgs = @('compose', '--project-directory', $repoRoot, '--project-name', $project, '-f', (Join-Path $PSScriptRoot 'flyway-baseline-rehearsal.compose.yml'))
$env:REHEARSAL_DB_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:REHEARSAL_NONCE = $nonce
$checker = Join-Path $PSScriptRoot 'restore-reconcile-check.ps1'
$failures = New-Object System.Collections.Generic.List[string]
$mysqlId = $null
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-reconcile-test-' + [Guid]::NewGuid().ToString('N'))

function Invoke-Docker { param([string[]]$Arguments) return (Invoke-NativeProcess -FileName docker -Arguments $Arguments -WorkingDirectory $repoRoot).StandardOutput }

function Invoke-SqlFile {
    param([string]$Path, [string]$Database = '')
    $target = '/tmp/' + [IO.Path]::GetFileName($Path)
    [void](Invoke-Docker -Arguments @('cp', $Path, "${mysqlId}:$target"))
    $shell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --default-character-set=utf8mb4 $1 < "$2"'
    [void](Invoke-Docker -Arguments @('exec', $mysqlId, 'sh', '-c', $shell, 'sh', $Database, $target))
}

function Invoke-SqlText {
    param([string]$Name, [string]$Sql)
    $path = Join-Path $tempDir $Name
    [IO.File]::WriteAllText($path, $Sql, (New-Object System.Text.UTF8Encoding($false)))
    Invoke-SqlFile -Path $path
}

function Invoke-Checker {
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-NativeProcessStartInfo -FileName 'powershell.exe' -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $checker, '-ContainerName', $mysqlId, '-Json') -WorkingDirectory $repoRoot
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $out = $stdoutTask.GetAwaiter().GetResult(); $err = $stderrTask.GetAwaiter().GetResult()
    if (-not [string]::IsNullOrWhiteSpace($err)) { throw "Checker stderr: $($err.Trim())" }
    $exit = $process.ExitCode; $process.Dispose()
    return [pscustomobject]@{ Exit = $exit; Report = ($out | ConvertFrom-Json) }
}

function Assert-That { param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { Write-Host "[PASS] $Name" } else { Write-Host "[FAIL] $Name $Detail"; $failures.Add($Name) }
}

function Get-OutboxCount { param($Report, [string]$Service, [string]$Status)
    $row = @($Report.outbox | Where-Object { $_.service -eq $Service -and $_.status -eq $Status }) | Select-Object -First 1
    if ($null -eq $row) { return 0 } else { return [long]$row.count }
}

function Get-Payload { param([string]$EventId, [string]$Type, [string]$Payload)
    return "{""eventId"":""$EventId"",""eventType"":""$Type"",""timestamp"":""2026-10-23T00:00:00"",""payload"":$Payload}"
}

$e1 = '11111111-1111-4111-8111-000000000001'; $e3 = '11111111-1111-4111-8111-000000000003'
$p1 = '22222222-2222-4222-8222-000000000001'; $p2 = '22222222-2222-4222-8222-000000000002'; $p3 = '22222222-2222-4222-8222-000000000003'
$t1 = '33333333-3333-4333-8333-000000000001'; $t2 = '33333333-3333-4333-8333-000000000002'
$n1 = '44444444-4444-4444-8444-000000000001'; $n2 = '44444444-4444-4444-8444-000000000002'

try {
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    [void](Invoke-NativeProcess -FileName docker -Arguments ($composeArgs + @('up', '-d', '--wait', 'mysql')) -WorkingDirectory $repoRoot)
    $ids = @(Get-OutputLines -Text (Invoke-Docker -Arguments ($composeArgs + @('ps', '-q', 'mysql'))))
    if ($ids.Count -ne 1) { throw 'Isolated MySQL container not found.' }
    $item = @((Invoke-Docker -Arguments @('inspect', $ids[0])) | ConvertFrom-Json)[0]
    if ([string]$item.Config.Labels.$labelKey -cne $nonce -or [string]$item.Config.Labels.'com.docker.compose.project' -cne $project -or
        [string]$item.Name -like '/buildflow-*') { throw 'Isolated MySQL identity check failed.' }
    $mysqlId = $ids[0]

    Invoke-SqlText -Name 'schemas.sql' -Sql ((@('estimate', 'purchase', 'tax', 'notification', 'site') | ForEach-Object { "CREATE DATABASE buildflow_$_ CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" }) -join "`n")
    foreach ($service in @('estimate', 'purchase', 'tax', 'notification', 'site')) {
        $dir = Join-Path $repoRoot "$service-service\src\main\resources\db\migration"
        foreach ($file in (Get-ChildItem -LiteralPath $dir -Filter 'V*.sql' | Sort-Object { [int](($_.Name -split '__')[0].Substring(1)) })) {
            Invoke-SqlFile -Path $file.FullName -Database "buildflow_$service"
        }
    }

    # Scenario at "backup time":
    #   site 1 consistent; site 2 confirmed estimate never reflected (UNEXPLAINED drift);
    #   site 3 confirmed estimate whose outbox event is still PENDING; site 4 purchase event SENT but
    #   not consumed by site-service; site 5 purchase event PENDING after failed attempts (no drift yet).
    $now = '2026-10-23 01:00:00'
    $seed = @"
INSERT INTO buildflow_estimate.estimates (id, created_at, estimate_date, site_id, status, title, total_amount) VALUES
 (1, '$now', '2026-10-01', 1, 'CONFIRMED', 'e1', 1000000.00),
 (2, '$now', '2026-10-01', 2, 'CONFIRMED', 'e2', 500000.00),
 (3, '$now', '2026-10-01', 3, 'CONFIRMED', 'e3', 200000.00),
 (4, '$now', '2026-10-01', 1, 'DRAFT', 'draft', 999.00);
INSERT INTO buildflow_estimate.outbox_events (event_id, attempts, created_at, next_attempt_at, payload_json, record_key, sent_at, status, topic) VALUES
 ('$e1', 1, '$now', '$now', '$(Get-Payload $e1 'ESTIMATE_PARSED' '{"estimateId":1,"siteId":1,"totalAmount":1000000.00}')', '1', '$now', 'SENT', 'estimate.parsed'),
 ('$e3', 0, '$now', '$now', '$(Get-Payload $e3 'ESTIMATE_PARSED' '{"estimateId":3,"siteId":3,"totalAmount":200000.00}')', '3', NULL, 'PENDING', 'estimate.parsed');
INSERT INTO buildflow_purchase.purchases (id, created_at, event_revision, item_name, quantity, site_id, total_amount, unit_price) VALUES
 (1, '$now', 1, 'p1', 1, 1, 300000.00, 300000.00),
 (2, '$now', 1, 'p2', 1, 4, 80000.00, 80000.00);
INSERT INTO buildflow_purchase.outbox_events (event_id, attempts, created_at, last_error, next_attempt_at, payload_json, record_key, sent_at, status, topic) VALUES
 ('$p1', 1, '$now', NULL, '$now', '$(Get-Payload $p1 'PURCHASE_REGISTERED' '{"purchaseId":1,"siteId":1,"revision":1,"totalAmount":300000.00}')', '1', '$now', 'SENT', 'purchase.registered'),
 ('$p2', 1, '$now', NULL, '$now', '$(Get-Payload $p2 'PURCHASE_REGISTERED' '{"purchaseId":2,"siteId":4,"revision":1,"totalAmount":80000.00}')', '2', '$now', 'SENT', 'purchase.registered'),
 ('$p3', 3, '$now', 'broker timeout', '$now', '$(Get-Payload $p3 'PURCHASE_DELETED' '{"purchaseId":9,"siteId":5,"revision":2,"totalAmount":0}')', '9', NULL, 'PENDING', 'purchase.deleted');
INSERT INTO buildflow_tax.outbox_events (event_id, attempts, claim_token, created_at, lease_until, next_attempt_at, payload_json, record_key, sent_at, status, topic) VALUES
 ('$t1', 1, NULL, '$now', NULL, '$now', '$(Get-Payload $t1 'TAX_REGISTERED' '{"taxInvoiceId":1,"siteId":1}')', '1', '$now', 'SENT', 'tax.registered'),
 ('$t2', 1, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '$now', '$now', '$now', '$(Get-Payload $t2 'TAX_PAYMENT_CONFIRMED' '{"taxInvoiceId":1,"siteId":1}')', '1', NULL, 'CLAIMED', 'tax.payment.confirmed');
INSERT INTO buildflow_notification.outbox_events (event_id, attempts, created_at, next_attempt_at, payload_json, record_key, sent_at, status, topic, suppressed_at) VALUES
 ('$n1', 1, '$now', '$now', '$(Get-Payload $n1 'WARRANTY_EXPIRING' '{"warrantyId":3,"siteId":1}')', '3', '$now', 'SENT', 'warranty.expiring', NULL),
 ('$n2', 0, '$now', '$now', '$(Get-Payload $n2 'WARRANTY_EXPIRING' '{"warrantyId":3,"siteId":1}')', '3', NULL, 'SUPPRESSED', 'warranty.expiring', '$now');
INSERT INTO buildflow_notification.processed_notification_events (event_id, processed_at) VALUES
 ('$e1', '$now'), ('$p1', '$now'), ('$p2', '$now'), ('$n1', '$now');
INSERT INTO buildflow_notification.defect_warranties (id, created_at, ocr_status, site_id) VALUES
 (1, '$now', 'PENDING', 1), (2, '$now', 'PENDING', 2), (3, '$now', 'MANUAL', 1);
INSERT INTO buildflow_site.processed_profit_events (event_id, event_type, site_id) VALUES
 ('$e1', 'ESTIMATE_PARSED', 1), ('$p1', 'PURCHASE_REGISTERED', 1);
INSERT INTO buildflow_site.purchase_profit_projections (purchase_id, current_amount, deleted, last_event_id, last_revision, site_id) VALUES
 (1, 300000.00, b'0', '$p1', 1, 1);
INSERT INTO buildflow_site.site_profits (created_at, margin, margin_rate, site_id, total_estimate_amount, total_purchase_amount) VALUES
 ('$now', 700000.00, 70.00, 1, 1000000.00, 300000.00);
"@
    Invoke-SqlText -Name 'seed.sql' -Sql $seed

    $a = Invoke-Checker
    $r = $a.Report
    $byClass = @{}; foreach ($f in $r.siteFindings) { $byClass[[string]$f.siteId] = $f }
    Assert-That 'A: verdict DRIFT / exit 3' ($r.verdict -eq 'DRIFT' -and $a.Exit -eq 3) "verdict=$($r.verdict) exit=$($a.Exit)"
    Assert-That 'A: OCR PENDING 2 (ids 1,2)' ($r.ocrPending.count -eq 2 -and (@($r.ocrPending.rows | ForEach-Object { $_.id }) -join ',') -eq '1,2')
    Assert-That 'A: outbox counts' ((Get-OutboxCount $r 'estimate' 'SENT') -eq 1 -and (Get-OutboxCount $r 'estimate' 'PENDING') -eq 1 -and
        (Get-OutboxCount $r 'purchase' 'SENT') -eq 2 -and (Get-OutboxCount $r 'purchase' 'PENDING') -eq 1 -and
        (Get-OutboxCount $r 'tax' 'SENT') -eq 1 -and (Get-OutboxCount $r 'tax' 'CLAIMED') -eq 1 -and
        (Get-OutboxCount $r 'notification' 'SENT') -eq 1 -and (Get-OutboxCount $r 'notification' 'SUPPRESSED') -eq 1 -and $r.unsentOutboxTotal -eq 3) ($r.outbox | ConvertTo-Json -Compress)
    $failedPending = @($r.outbox | Where-Object { $_.service -eq 'purchase' -and $_.status -eq 'PENDING' })[0]
    Assert-That 'A: failed-attempt PENDING visible (retried/error)' ($failedPending.retried -eq 1 -and $failedPending.withError -eq 1)
    Assert-That 'A: exactly sites 2,3,4 flagged' ((@($r.siteFindings | ForEach-Object { $_.siteId }) -join ',') -eq '2,3,4') (@($r.siteFindings | ForEach-Object { $_.siteId }) -join ',')
    Assert-That 'A: site 2 UNEXPLAINED (estimate 0 vs 500000, no profit row)' ($byClass['2'].classification -eq 'UNEXPLAINED' -and [decimal]$byClass['2'].profit.estimateExpected -eq 500000 -and [decimal]$byClass['2'].profit.estimateActual -eq 0 -and -not $byClass['2'].profit.profitRow)
    Assert-That 'A: site 3 PENDING_OUTBOX' ($byClass['3'].classification -eq 'PENDING_OUTBOX')
    Assert-That 'A: site 4 UNCONSUMED_SENT with requeue candidate p2 + projection MISSING' ($byClass['4'].classification -eq 'UNCONSUMED_SENT' -and
        @($byClass['4'].requeueCandidates).Count -eq 1 -and $byClass['4'].requeueCandidates[0].eventId -eq $p2 -and
        @($byClass['4'].projections | Where-Object { $_.purchaseId -eq 2 -and $_.reason -eq 'MISSING' }).Count -eq 1)
    Assert-That 'A: site unconsumed 1, none without drift' ($r.unconsumedSentForSite.total -eq 1 -and $r.unconsumedSentForSite.withoutProfitDrift -eq 0)
    Assert-That 'A: notification unconsumed = tax.registered t1 only' ($r.unconsumedSentForNotification.total -eq 1 -and $r.unconsumedSentForNotification.sample[0].eventId -eq $t1)

    # Converge: every gap is closed the way the runbook expects (resend/consume/manual fix).
    $fix = @"
UPDATE buildflow_notification.defect_warranties SET ocr_status = 'FAILED' WHERE ocr_status = 'PENDING';
UPDATE buildflow_estimate.outbox_events SET status = 'SENT', sent_at = '$now' WHERE event_id = '$e3';
UPDATE buildflow_purchase.outbox_events SET status = 'SENT', sent_at = '$now' WHERE event_id = '$p3';
UPDATE buildflow_tax.outbox_events SET status = 'SENT', sent_at = '$now', claim_token = NULL, lease_until = NULL WHERE event_id = '$t2';
INSERT INTO buildflow_site.processed_profit_events (event_id, event_type, site_id) VALUES ('$e3', 'ESTIMATE_PARSED', 3), ('$p2', 'PURCHASE_REGISTERED', 4), ('$p3', 'PURCHASE_DELETED', 5);
INSERT INTO buildflow_notification.processed_notification_events (event_id, processed_at) VALUES ('$e3', '$now'), ('$p3', '$now'), ('$t1', '$now'), ('$t2', '$now');
INSERT INTO buildflow_site.purchase_profit_projections (purchase_id, current_amount, deleted, last_event_id, last_revision, site_id) VALUES (2, 80000.00, b'0', '$p2', 1, 4);
INSERT INTO buildflow_site.site_profits (created_at, margin, margin_rate, site_id, total_estimate_amount, total_purchase_amount) VALUES
 ('$now', 500000.00, 100.00, 2, 500000.00, 0.00), ('$now', 200000.00, 100.00, 3, 200000.00, 0.00), ('$now', -80000.00, 0.00, 4, 0.00, 80000.00);
"@
    Invoke-SqlText -Name 'fix.sql' -Sql $fix
    $b = Invoke-Checker
    $r = $b.Report
    Assert-That 'B: converged state verdict OK / exit 0' ($r.verdict -eq 'OK' -and $b.Exit -eq 0) "verdict=$($r.verdict) exit=$($b.Exit)"
    Assert-That 'B: no findings' ($r.ocrPending.count -eq 0 -and @($r.siteFindings).Count -eq 0 -and $r.unsentOutboxTotal -eq 0 -and
        $r.unconsumedSentForSite.total -eq 0 -and $r.unconsumedSentForNotification.total -eq 0)
}
finally {
    if ($null -ne $mysqlId) {
        $ids = @(Get-OutputLines -Text (Invoke-Docker -Arguments @('ps', '-aq', '--filter', "label=com.docker.compose.project=$project")))
        $safe = $ids.Count -eq 1
        foreach ($id in $ids) {
            $item = @((Invoke-Docker -Arguments @('inspect', $id)) | ConvertFrom-Json)[0]
            if ([string]$item.Config.Labels.$labelKey -cne $nonce) { $safe = $false }
        }
        if ($safe) { [void](Invoke-NativeProcess -FileName docker -Arguments ($composeArgs + @('down', '--timeout', '10')) -WorkingDirectory $repoRoot) }
        else { Write-Host "[WARN] Isolated project $project identity changed; left for inspection." }
    }
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ((Test-Path -LiteralPath $tempDir) -and [IO.Path]::GetFullPath($tempDir).StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $tempDir -Recurse
    }
    Remove-Item Env:\REHEARSAL_DB_PASSWORD, Env:\REHEARSAL_NONCE -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) { throw "Restore reconcile regression failed: $($failures -join '; ')" }
Write-Host '[OK] restore-reconcile-check reported the synthetic restore gaps exactly and converged to OK.'
