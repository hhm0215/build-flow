[CmdletBinding()]
param(
    # MySQL container that holds the restored buildflow_* schemas (ID or name).
    [Parameter(Mandatory = $true)][string]$ContainerName,
    # Print one JSON document instead of the human-readable report.
    [switch]$Json,
    [ValidateRange(1, 500)][int]$SampleLimit = 20
)

# Read-only post-restore reconciliation check (docs/DATABASE_OPERATIONS.md "복원 후 재조정").
# Runs every query in one READ ONLY transaction with a consistent snapshot and reports:
#   - notification defect_warranties.ocr_status = PENDING (the in-memory OCR queue is not restored)
#   - per-service outbox counts by status (PENDING/CLAIMED are re-sent automatically after start)
#   - SENT outbox events without a consumer receipt (processed_profit_events / processed_notification_events)
#   - site_profits vs. sources (SUM confirmed estimates, SUM purchases) and purchase projections vs. purchases
# Exit code: 0 = OK, 2 = ATTENTION (automatic or operator follow-up, no unexplained drift), 3 = DRIFT.
# It never writes, never replays Kafka topics and never prints credentials.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

if ($ContainerName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$') { throw 'Unexpected container name.' }

$siteTopics = @('estimate.parsed', 'estimate.deleted', 'purchase.registered', 'purchase.updated', 'purchase.deleted')
$notificationTopics = $siteTopics + @('tax.registered', 'tax.payment.confirmed', 'warranty.expiring')
$outboxSchemas = @('estimate', 'purchase', 'tax', 'notification')

function ConvertTo-SqlList { param([string[]]$Values) return (($Values | ForEach-Object { "'$_'" }) -join ',') }

function Get-SiteIdExpression {
    param([string]$Alias)
    return "IF(JSON_VALID($Alias.payload_json), JSON_UNQUOTE(JSON_EXTRACT($Alias.payload_json, '$.payload.siteId')), NULL)"
}

$queries = New-Object System.Collections.Generic.List[string]
$queries.Add("SELECT 'SCHEMA', SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME IN ('buildflow_estimate','buildflow_purchase','buildflow_tax','buildflow_notification','buildflow_site') ORDER BY SCHEMA_NAME")
$queries.Add("SELECT 'OCR', id, site_id, DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s') FROM buildflow_notification.defect_warranties WHERE ocr_status = 'PENDING' ORDER BY id")
foreach ($schema in $outboxSchemas) {
    $queries.Add("SELECT 'OUTBOX', '$schema', status, COUNT(*), SUM(attempts > 0), SUM(last_error IS NOT NULL) FROM buildflow_$schema.outbox_events GROUP BY status ORDER BY status")
}
foreach ($schema in @('estimate', 'purchase')) {
    $queries.Add("SELECT 'UNSENT', '$schema', o.event_id, o.topic, $(Get-SiteIdExpression -Alias 'o') FROM buildflow_$schema.outbox_events o WHERE o.status IN ('PENDING','CLAIMED') ORDER BY o.created_at, o.event_id")
    $queries.Add("SELECT 'UNCONSUMED', 'site', '$schema', o.event_id, o.topic, $(Get-SiteIdExpression -Alias 'o') FROM buildflow_$schema.outbox_events o LEFT JOIN buildflow_site.processed_profit_events p ON p.event_id = o.event_id WHERE o.status = 'SENT' AND o.topic IN ($(ConvertTo-SqlList $siteTopics)) AND p.event_id IS NULL ORDER BY o.sent_at, o.event_id")
}
foreach ($schema in $outboxSchemas) {
    $queries.Add("SELECT 'UNCONSUMED', 'notification', '$schema', o.event_id, o.topic, $(Get-SiteIdExpression -Alias 'o') FROM buildflow_$schema.outbox_events o LEFT JOIN buildflow_notification.processed_notification_events p ON p.event_id = o.event_id WHERE o.status = 'SENT' AND o.topic IN ($(ConvertTo-SqlList $notificationTopics)) AND p.event_id IS NULL ORDER BY o.sent_at, o.event_id")
}
$queries.Add(@"
SELECT 'PROFIT', s.site_id, sp.site_id IS NOT NULL,
       COALESCE(sp.total_estimate_amount, 0), COALESCE(e.amount, 0),
       COALESCE(sp.total_purchase_amount, 0), COALESCE(pu.amount, 0), COALESCE(sp.margin, 0)
  FROM (SELECT site_id FROM buildflow_site.site_profits
        UNION SELECT site_id FROM buildflow_estimate.estimates WHERE status = 'CONFIRMED'
        UNION SELECT site_id FROM buildflow_purchase.purchases) s
  LEFT JOIN buildflow_site.site_profits sp ON sp.site_id = s.site_id
  LEFT JOIN (SELECT site_id, SUM(total_amount) amount FROM buildflow_estimate.estimates WHERE status = 'CONFIRMED' GROUP BY site_id) e ON e.site_id = s.site_id
  LEFT JOIN (SELECT site_id, SUM(total_amount) amount FROM buildflow_purchase.purchases GROUP BY site_id) pu ON pu.site_id = s.site_id
 WHERE COALESCE(sp.total_estimate_amount, 0) <> COALESCE(e.amount, 0)
    OR COALESCE(sp.total_purchase_amount, 0) <> COALESCE(pu.amount, 0)
    OR COALESCE(sp.margin, 0) <> COALESCE(sp.total_estimate_amount, 0) - COALESCE(sp.total_purchase_amount, 0)
 ORDER BY s.site_id
"@)
$queries.Add(@"
SELECT 'PROJECTION', p.id, p.site_id,
       CASE WHEN pp.purchase_id IS NULL THEN 'MISSING'
            WHEN pp.deleted = 1 THEN 'MARKED_DELETED'
            WHEN pp.site_id <> p.site_id THEN 'SITE'
            WHEN pp.last_revision <> p.event_revision THEN 'REVISION'
            ELSE 'AMOUNT' END
  FROM buildflow_purchase.purchases p
  LEFT JOIN buildflow_site.purchase_profit_projections pp ON pp.purchase_id = p.id
 WHERE pp.purchase_id IS NULL OR pp.deleted = 1 OR pp.site_id <> p.site_id
    OR pp.last_revision <> p.event_revision OR pp.current_amount <> p.total_amount
UNION ALL
SELECT 'PROJECTION', pp.purchase_id, pp.site_id, 'ORPHAN'
  FROM buildflow_site.purchase_profit_projections pp
  LEFT JOIN buildflow_purchase.purchases p ON p.id = pp.purchase_id
 WHERE pp.deleted = 0 AND p.id IS NULL
 ORDER BY 2
"@)

$body = ($queries | ForEach-Object { ($_ -replace '\s+', ' ').Trim() + ';' }) -join ' '
$sql = "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY; $body COMMIT;"
$shell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names --default-character-set=utf8mb4 --execute="$1"'
$result = Invoke-NativeProcess -FileName docker -Arguments @('exec', $ContainerName, 'sh', '-c', $shell, 'sh', $sql) -WorkingDirectory $PSScriptRoot
$lines = @(Get-OutputLines -Text $result.StandardOutput)

$schemas = @(); $ocr = @(); $outbox = @(); $unsent = @(); $unconsumed = @(); $profit = @(); $projection = @()
foreach ($line in $lines) {
    $f = $line -split "`t"
    switch ($f[0]) {
        'SCHEMA' { $schemas += $f[1] }
        'OCR' { $ocr += [pscustomobject]@{ id = [long]$f[1]; siteId = [long]$f[2]; createdAt = $f[3] } }
        'OUTBOX' { $outbox += [pscustomobject]@{ service = $f[1]; status = $f[2]; count = [long]$f[3]; retried = [long]$f[4]; withError = [long]$f[5] } }
        'UNSENT' { $unsent += [pscustomobject]@{ source = $f[1]; eventId = $f[2]; topic = $f[3]; siteId = $f[4] } }
        'UNCONSUMED' { $unconsumed += [pscustomobject]@{ consumer = $f[1]; source = $f[2]; eventId = $f[3]; topic = $f[4]; siteId = $f[5] } }
        'PROFIT' {
            $profit += [pscustomobject]@{
                siteId = [long]$f[1]; profitRow = ($f[2] -eq '1')
                estimateActual = [decimal]$f[3]; estimateExpected = [decimal]$f[4]
                purchaseActual = [decimal]$f[5]; purchaseExpected = [decimal]$f[6]; margin = [decimal]$f[7]
            }
        }
        'PROJECTION' { $projection += [pscustomobject]@{ purchaseId = [long]$f[1]; siteId = [long]$f[2]; reason = $f[3] } }
        default { throw "Unexpected reconcile output row: $($f[0])" }
    }
}
$missingSchemas = @('buildflow_estimate', 'buildflow_notification', 'buildflow_purchase', 'buildflow_site', 'buildflow_tax') | Where-Object { $schemas -notcontains $_ }
if (@($missingSchemas).Count -gt 0) { throw "Restored schemas missing: $($missingSchemas -join ', ')" }

$unsentSites = @($unsent | Where-Object { $_.siteId -match '^[0-9]+$' } | ForEach-Object { [long]$_.siteId } | Sort-Object -Unique)
$siteUnconsumed = @($unconsumed | Where-Object { $_.consumer -eq 'site' })
$unconsumedSites = @($siteUnconsumed | Where-Object { $_.siteId -match '^[0-9]+$' } | ForEach-Object { [long]$_.siteId } | Sort-Object -Unique)
$driftSites = @(@($profit | ForEach-Object { $_.siteId }) + @($projection | ForEach-Object { $_.siteId }) | Sort-Object -Unique)

$siteFindings = @(foreach ($siteId in $driftSites) {
    $class = if ($unsentSites -contains $siteId) { 'PENDING_OUTBOX' }
             elseif ($unconsumedSites -contains $siteId) { 'UNCONSUMED_SENT' }
             else { 'UNEXPLAINED' }
    [pscustomobject]@{
        siteId = $siteId; classification = $class
        profit = @($profit | Where-Object { $_.siteId -eq $siteId }) | Select-Object -First 1
        projections = @($projection | Where-Object { $_.siteId -eq $siteId })
        requeueCandidates = @(if ($class -eq 'UNCONSUMED_SENT') {
            $siteUnconsumed | Where-Object { $_.siteId -eq [string]$siteId } | ForEach-Object { [pscustomobject]@{ source = $_.source; eventId = $_.eventId; topic = $_.topic } }
        })
    }
})
$noEffectSiteUnconsumed = @($siteUnconsumed | Where-Object { -not ($_.siteId -match '^[0-9]+$' -and $driftSites -contains [long]$_.siteId) })
$notificationUnconsumed = @($unconsumed | Where-Object { $_.consumer -eq 'notification' })
$unsentTotal = [long]0
foreach ($row in $outbox) { if ($row.status -in @('PENDING', 'CLAIMED')) { $unsentTotal += $row.count } }

$verdict = 'OK'
if ($ocr.Count -gt 0 -or $unsentTotal -gt 0 -or @($siteFindings | Where-Object { $_.classification -ne 'UNEXPLAINED' }).Count -gt 0) { $verdict = 'ATTENTION' }
if (@($siteFindings | Where-Object { $_.classification -eq 'UNEXPLAINED' }).Count -gt 0) { $verdict = 'DRIFT' }

$report = [pscustomobject]@{
    verdict = $verdict
    ocrPending = [pscustomobject]@{ count = $ocr.Count; rows = @($ocr | Select-Object -First $SampleLimit) }
    outbox = @($outbox)
    unsentOutboxTotal = $unsentTotal
    siteFindings = @($siteFindings)
    unconsumedSentForSite = [pscustomobject]@{ total = $siteUnconsumed.Count; withoutProfitDrift = $noEffectSiteUnconsumed.Count; sample = @($noEffectSiteUnconsumed | Select-Object -First $SampleLimit) }
    unconsumedSentForNotification = [pscustomobject]@{ total = $notificationUnconsumed.Count; sample = @($notificationUnconsumed | Select-Object -First $SampleLimit) }
}

if ($Json) {
    $report | ConvertTo-Json -Depth 8
}
else {
    # ASCII-only output: Windows PowerShell 5.1 reads BOM-less UTF-8 scripts as ANSI.
    Write-Host "[VERDICT] $verdict  (exit 0=OK, 2=ATTENTION, 3=DRIFT)"
    Write-Host "[OCR] PENDING rows: $($ocr.Count) (in-memory OCR queue is not restored; notification-service marks rows older than 30 min FAILED, then fix manually)"
    foreach ($row in ($ocr | Select-Object -First $SampleLimit)) { Write-Host "       warranty id=$($row.id) site=$($row.siteId) created=$($row.createdAt)" }
    Write-Host '[OUTBOX] rows by service/status (PENDING/CLAIMED are re-sent with the same eventId after start)'
    foreach ($row in $outbox) { Write-Host ("       {0,-12} {1,-10} {2,6}  retried={3} withError={4}" -f $row.service, $row.status, $row.count, $row.retried, $row.withError) }
    Write-Host "[PROFIT] sites whose site_profits/projections differ from sources: $($siteFindings.Count)"
    foreach ($item in $siteFindings) {
        $p = $item.profit
        $detail = if ($null -ne $p) { "estimate $($p.estimateActual)/source $($p.estimateExpected), purchase $($p.purchaseActual)/source $($p.purchaseExpected), profitRow=$($p.profitRow)" } else { 'site_profits totals match' }
        $proj = if ($item.projections.Count -gt 0) { ' / projection: ' + (($item.projections | ForEach-Object { "purchase $($_.purchaseId)=$($_.reason)" }) -join ', ') } else { '' }
        Write-Host "       site=$($item.siteId) [$($item.classification)] $detail$proj"
        foreach ($c in $item.requeueCandidates) { Write-Host "         requeue candidate $($c.source) $($c.topic) eventId=$($c.eventId)" }
    }
    Write-Host "[UNCONSUMED SENT] site receipts missing: $($siteUnconsumed.Count) (on sites without drift: $($noEffectSiteUnconsumed.Count), do not requeue), notification receipts missing: $($notificationUnconsumed.Count) (lost alerts, informational)"
}

switch ($verdict) { 'OK' { exit 0 } 'ATTENTION' { exit 2 } default { exit 3 } }
