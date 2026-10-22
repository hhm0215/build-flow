[CmdletBinding()]
param(
    # Checkout of the release currently in production with tax/site boot jars built, e.g.
    #   git archive <prod-sha> | tar -x -C <dir>;  <dir>\gradlew.bat -p <dir> :tax-service:bootJar :site-service:bootJar -x test
    [Parameter(Mandatory = $true)][string]$OldRoot,
    # Checkout of the candidate release (default: this repository) with tax/site boot jars built.
    [string]$NewRoot = '',
    [string]$OutputDirectory = '',
    [ValidateRange(60, 900)][int]$StartupTimeoutSeconds = 300
)

# Isolated upgrade/rollback rehearsal for tax-service split payments (tax_payments + legacy backfill)
# and site-service BIDDING status, both on the production path (Hibernate ddl-auto update):
#   old code builds the schema + synthetic rows -> new code upgrades in place -> restart (no duplicate
#   backfill) -> old code again (rollback, BIDDING lowered per runbook) -> new code again (re-upgrade).
# Touches only a random Compose project (bfupg<random>) whose resources carry a nonce label.
# Never uses the existing `buildflow` project, host ports, named volumes, `down -v` or force flags.

# Strict mode off: API responses (failure bodies have no data) are probed for optional fields.
Set-StrictMode -Off
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'flyway-ddl-compare.ps1')

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($NewRoot)) { $NewRoot = $repoRoot }
$nonce = [Guid]::NewGuid().ToString('N')
$project = 'bfupg' + (Get-Random -Minimum 10000 -Maximum 99999)
$labelKey = 'com.buildflow.flyway-rehearsal'   # label key defined by the shared isolated-MySQL compose file
$appImage = 'eclipse-temurin:17-jre-jammy'
$composeArgs = @('compose', '--project-directory', $repoRoot, '--project-name', $project, '-f', (Join-Path $PSScriptRoot 'flyway-baseline-rehearsal.compose.yml'))
$dbPassword = 'Rh' + [Guid]::NewGuid().ToString('N')
$jwtSecret = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$environmentNames = @('REHEARSAL_DB_PASSWORD', 'REHEARSAL_NONCE')
$previous = @{}
$started = $false
$script:exitCode = 1
$appContainers = New-Object System.Collections.Generic.List[string]
$results = New-Object System.Collections.Generic.List[object]
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-upgrade-rehearsal-' + $nonce.Substring(0, 12))
}
$ports = @{ tax = 8085; site = 8083 }

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return (Invoke-NativeProcess -FileName 'docker' -Arguments $Arguments -WorkingDirectory $repoRoot).StandardOutput
}

function Get-JarPath {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Service)
    $dir = Join-Path $Root "$Service-service\build\libs"
    $jar = @(Get-ChildItem -Path $dir -Filter "$Service-service-*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*-plain.jar' })
    if ($jar.Count -ne 1) { throw "Expected exactly one boot jar in $dir." }
    return $jar[0].FullName
}

function Invoke-Sql {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $text = Invoke-Docker -Arguments @('exec', '-e', "MYSQL_PWD=$dbPassword", $script:mysqlId, 'mysql', '--user=root', '--batch', '--skip-column-names', '--default-character-set=utf8mb4', "--execute=$Sql")
    return @(Get-OutputLines -Text $text)
}

function Add-Check {
    # -WarnOnly: a known rollback-path risk is reported as WARN and does not fail the run.
    param([string]$Phase, [string]$Name, [bool]$Pass, [string]$Evidence, [switch]$WarnOnly)
    $result = if ($Pass) { 'PASS' } elseif ($WarnOnly) { 'WARN' } else { 'FAIL' }
    $results.Add([pscustomobject]@{ Phase = $Phase; Check = $Name; Result = $result; Evidence = $Evidence })
    Write-Host ('[{0}] {1,-4} {2} :: {3}' -f $Phase, $result, $Name, $Evidence)
}

function Start-App {
    param([string]$Service, [string]$Root, [string]$Phase)
    $name = "$project-$Service"
    $url = "jdbc:mysql://mysql:3306/buildflow_$Service" + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8'
    $arguments = @(
        'run', '-d', '--name', $name, '--network', "${project}_default", '--network-alias', "$Service-app",
        '--label', "$labelKey=$nonce", '--label', "$labelKey.project=$project",
        '--mount', ('type=bind,source=' + (Get-JarPath -Root $Root -Service $Service) + ',target=/app/app.jar,readonly'),
        '-e', "DB_PASSWORD=$dbPassword", '-e', "JWT_SECRET=$jwtSecret", '-e', 'TZ=Asia/Seoul',
        '-e', 'CONFIG_SERVER_URL=http://127.0.0.1:1', '-e', 'KAFKA_BOOTSTRAP_SERVERS=mysql:9', '-e', 'REDIS_HOST=127.0.0.1',
        $appImage, 'java', '-Xmx384m', '-jar', '/app/app.jar',
        "--spring.datasource.url=$url", '--spring.cloud.config.enabled=false', '--eureka.client.enabled=false',
        '--spring.kafka.admin.auto-create=false', '--spring.kafka.listener.auto-startup=false',
        # Feign tax -> site without Eureka.
        '--spring.cloud.discovery.client.simple.instances.site-service[0].uri=http://site-app:8083'
    )
    [void](Invoke-Docker -Arguments $arguments)
    $appContainers.Add($name)
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ($true) {
        $proc = Invoke-NativeProcess -FileName 'docker' -Arguments @('logs', $name) -WorkingDirectory $repoRoot
        $log = $proc.StandardOutput + "`n" + $proc.StandardError
        if ($log -match 'Started \S+Application in') { $outcome = 'STARTED'; break }
        if ((Invoke-Docker -Arguments @('inspect', '--format', '{{.State.Running}}', $name)).Trim() -ne 'true') { $outcome = 'EXITED'; break }
        if ((Get-Date) -gt $deadline) { $outcome = 'TIMEOUT'; break }
        Start-Sleep -Seconds 3
    }
    return [pscustomobject]@{ Name = $name; Outcome = $outcome; Phase = $Phase }
}

function Stop-App {
    param([Parameter(Mandatory = $true)][string]$Service, [string]$Phase = 'stop')
    $name = "$project-$Service"
    $item = @((Invoke-Docker -Arguments @('inspect', $name) | ConvertFrom-Json))[0]
    if ([string]$item.Config.Labels.$labelKey -cne $nonce -or [string]$item.Config.Labels."$labelKey.project" -cne $project) {
        throw "Refusing to remove unlabelled container: $name"
    }
    $proc = Invoke-NativeProcess -FileName 'docker' -Arguments @('logs', $name) -WorkingDirectory $repoRoot
    [IO.File]::WriteAllText((Join-Path $OutputDirectory "$Phase-$Service.log"), $proc.StandardOutput + "`n" + $proc.StandardError, (New-Object System.Text.UTF8Encoding($false)))
    if ($item.State.Running) { [void](Invoke-Docker -Arguments @('stop', '--time', '10', $name)) }
    [void](Invoke-Docker -Arguments @('rm', $name))
    [void]$appContainers.Remove($name)
}

# HTTP inside the app container (no host ports). Returns @{ Status; Body(object or $null) }.
function Invoke-Api {
    param([string]$Service, [string]$Method, [string]$Path, [object]$Body = $null)
    $arguments = @('exec', "$project-$Service", 'curl', '-s', '-X', $Method, '-w', "`n%{http_code}", "http://localhost:$($ports[$Service])$Path")
    if ($null -ne $Body) { $arguments += @('-H', 'Content-Type: application/json', '-d', ($Body | ConvertTo-Json -Compress)) }
    $lines = (Invoke-Docker -Arguments $arguments) -split "`n"
    $status = [int]$lines[-1].Trim()
    $text = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    $parsed = $null
    if ($text.StartsWith('{')) { try { $parsed = $text | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ Status = $status; Body = $parsed }
}

function Get-Outstanding { param([long]$SiteId) return (Invoke-Api -Service tax -Method GET -Path "/api/v1/taxes/outstanding?siteId=$SiteId").Body.data }
function Get-PaymentRows { return @(Invoke-Sql -Sql "SELECT CONCAT_WS('|',tax_invoice_id,amount,IFNULL(memo,'-'),IF(canceled_at IS NULL,'active','canceled')) FROM buildflow_tax.tax_payments ORDER BY id") }
function Get-StatusColumn { return @(Invoke-Sql -Sql "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='buildflow_site' AND TABLE_NAME='sites' AND COLUMN_NAME='status'")[0] }

try {
    $oldTax = Get-JarPath -Root $OldRoot -Service tax; $oldSite = Get-JarPath -Root $OldRoot -Service site
    $newTax = Get-JarPath -Root $NewRoot -Service tax; $newSite = Get-JarPath -Root $NewRoot -Service site
    if ((Get-FileHash $oldTax).Hash -eq (Get-FileHash $newTax).Hash) { throw 'Old and new tax-service jars are identical.' }
    [void](New-Item -ItemType Directory -Force -Path $OutputDirectory)
    foreach ($name in $environmentNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    [Environment]::SetEnvironmentVariable('REHEARSAL_DB_PASSWORD', $dbPassword, 'Process')
    [Environment]::SetEnvironmentVariable('REHEARSAL_NONCE', $nonce, 'Process')

    Write-Host "Rehearsal project: $project (label $labelKey=$($nonce.Substring(0, 12))...), output: $OutputDirectory"
    $started = $true
    [void](Invoke-NativeProcess -FileName 'docker' -Arguments ($composeArgs + @('up', '-d', '--wait', 'mysql')) -WorkingDirectory $repoRoot)
    $ids = @(Get-OutputLines -Text (Invoke-Docker -Arguments ($composeArgs + @('ps', '-q', 'mysql'))))
    if ($ids.Count -ne 1) { throw 'Rehearsal MySQL container not found.' }
    $item = @((Invoke-Docker -Arguments @('inspect', $ids[0]) | ConvertFrom-Json))[0]
    if ([string]$item.Config.Labels.$labelKey -cne $nonce -or [string]$item.Config.Labels.'com.docker.compose.project' -cne $project) { throw 'Rehearsal MySQL identity check failed.' }
    $script:mysqlId = $ids[0]
    foreach ($schema in @('buildflow_tax', 'buildflow_site')) { [void](Invoke-Sql -Sql "CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci") }
    $today = (Get-Date).ToString('yyyy-MM-dd')

    # 1) Old code: production-shaped schema + synthetic rows through the old API.
    $p = 'old'
    $r = Start-App -Service site -Root $OldRoot -Phase $p; Add-Check $p 'site-service(old) ddl-auto start' ($r.Outcome -eq 'STARTED') $r.Outcome
    $r = Start-App -Service tax -Root $OldRoot -Phase $p; Add-Check $p 'tax-service(old) ddl-auto start' ($r.Outcome -eq 'STARTED') $r.Outcome
    $siteIds = @()
    foreach ($n in 1..2) { $siteIds += [long](Invoke-Api -Service site -Method POST -Path '/api/v1/sites' -Body @{ siteName = "[QA] synthetic site $n" }).Body.data.id }
    $s1 = $siteIds[0]
    $inv = @{}
    foreach ($spec in @(@{ k = 'unpaid'; s = 1000000; t = 100000 }, @{ k = 'paid'; s = 500000; t = 50000 }, @{ k = 'zero'; s = 0; t = 0 })) {
        $resp = Invoke-Api -Service tax -Method POST -Path '/api/v1/taxes' -Body @{ siteId = $s1; type = 'SALES'; supplyAmount = $spec.s; taxAmount = $spec.t; counterparty = '[QA] synthetic'; issueDate = $today }
        $inv[$spec.k] = [long]$resp.Body.data.id
    }
    $resp = Invoke-Api -Service tax -Method PATCH -Path "/api/v1/taxes/$($inv.paid)/confirm-payment" -Body @{ paymentDate = '2026-10-01' }
    Add-Check $p 'synthetic rows (2 sites, 3 sales invoices, 1 confirmed)' ($siteIds.Count -eq 2 -and $inv.Values.Count -eq 3 -and $resp.Status -eq 200) "sites=$($siteIds -join ',') invoices=unpaid:$($inv.unpaid),paid:$($inv.paid),zero:$($inv.zero) confirm=$($resp.Status)"
    $oldColumn = Get-StatusColumn
    $hasPayments = @(Invoke-Sql -Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='buildflow_tax' AND TABLE_NAME='tax_payments'")[0]
    Add-Check $p 'old schema shape' ($oldColumn -notmatch 'BIDDING' -and $hasPayments -eq '0') "status=$oldColumn tax_payments=$hasPayments"
    Stop-App tax $p; Stop-App site $p

    # 2) New code on the same schema (ddl-auto update).
    $p = 'new'
    $r = Start-App -Service site -Root $NewRoot -Phase $p; Add-Check $p 'site-service(new) ddl-auto start' ($r.Outcome -eq 'STARTED') $r.Outcome
    $r = Start-App -Service tax -Root $NewRoot -Phase $p; Add-Check $p 'tax-service(new) ddl-auto start' ($r.Outcome -eq 'STARTED') $r.Outcome
    $ddl = @(Invoke-Sql -Sql 'SHOW CREATE TABLE buildflow_tax.tax_payments')[0]
    $ddl = ConvertFrom-MySqlBatchEscape -Text ($ddl -split "`t", 2)[1]
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'tax_payments.sql'), $ddl, (New-Object System.Text.UTF8Encoding($false)))
    Add-Check $p 'tax_payments created' ($ddl -match 'CREATE TABLE `tax_payments`' -and $ddl -match '`amount` decimal\(15,2\) NOT NULL' -and $ddl -match 'idx_tax_payments_invoice') (($ddl -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^`|KEY' }) -join ' ')
    $rows = @(Get-PaymentRows)
    Add-Check $p 'legacy backfill = confirmed invoice only' ($rows.Count -eq 1 -and $rows[0] -like "$($inv.paid)|550000.00|*|active") ($rows -join '; ')
    $col = Get-StatusColumn
    Add-Check $p 'sites.status enum has BIDDING' ($col -match "'BIDDING'") $col
    $o = Get-Outstanding $s1
    Add-Check $p 'outstanding after upgrade' ([decimal]$o.outstandingAmount -eq 1100000 -and [decimal]$o.confirmedAmount -eq 550000 -and $o.unpaidCount -eq 1) "total=$($o.totalSalesAmount) confirmed=$($o.confirmedAmount) outstanding=$($o.outstandingAmount) unpaid=$($o.unpaidCount)"
    $pay = Invoke-Api -Service tax -Method POST -Path "/api/v1/taxes/$($inv.unpaid)/payments" -Body @{ amount = 300000; paymentDate = $today; memo = 'qa partial' }
    $o = Get-Outstanding $s1
    Add-Check $p 'partial payment 300000' ($pay.Status -eq 201 -and [decimal]$o.outstandingAmount -eq 800000) "status=$($pay.Status) outstanding=$($o.outstandingAmount)"
    $over = Invoke-Api -Service tax -Method POST -Path "/api/v1/taxes/$($inv.unpaid)/payments" -Body @{ amount = 800001; paymentDate = $today }
    $zero = Invoke-Api -Service tax -Method POST -Path "/api/v1/taxes/$($inv.zero)/payments" -Body @{ amount = 1; paymentDate = $today }
    Add-Check $p 'over-balance payment rejected' ($over.Status -eq 400 -and $zero.Status -eq 400) "remaining+1=$($over.Status); zero-invoice=$($zero.Status)"
    $cancel = Invoke-Api -Service tax -Method PATCH -Path "/api/v1/taxes/$($inv.unpaid)/payments/$($pay.Body.data.id)/cancel" -Body @{ reason = 'qa cancel' }
    $o = Get-Outstanding $s1
    Add-Check $p 'cancel payment' ($cancel.Status -eq 200 -and [decimal]$o.outstandingAmount -eq 1100000) "status=$($cancel.Status) outstanding=$($o.outstandingAmount)"
    $legacyId = [long](@(Invoke-Sql -Sql "SELECT id FROM buildflow_tax.tax_payments WHERE tax_invoice_id=$($inv.paid)")[0])
    $c2 = Invoke-Api -Service tax -Method PATCH -Path "/api/v1/taxes/$($inv.paid)/payments/$legacyId/cancel" -Body @{ reason = 'qa' }
    $paidInv = (Invoke-Api -Service tax -Method GET -Path "/api/v1/taxes/$($inv.paid)").Body.data
    $re = Invoke-Api -Service tax -Method PATCH -Path "/api/v1/taxes/$($inv.paid)/confirm-payment" -Body @{ paymentDate = '2026-10-02' }
    $paidInv2 = (Invoke-Api -Service tax -Method GET -Path "/api/v1/taxes/$($inv.paid)").Body.data
    Add-Check $p 'cancel migrated row, then legacy confirm-payment' ($c2.Status -eq 200 -and -not $paidInv.paymentConfirmed -and $re.Status -eq 200 -and $paidInv2.paymentConfirmed -and [decimal]$paidInv2.paidAmount -eq 550000) "cancel=$($c2.Status) confirmed=$($paidInv.paymentConfirmed) -> confirm=$($re.Status) confirmed=$($paidInv2.paymentConfirmed) paid=$($paidInv2.paidAmount)"
    # Leave an active partial payment for the rollback scenario.
    $pay2 = Invoke-Api -Service tax -Method POST -Path "/api/v1/taxes/$($inv.unpaid)/payments" -Body @{ amount = 300000; paymentDate = $today }
    $bid = Invoke-Api -Service site -Method POST -Path '/api/v1/sites' -Body @{ siteName = '[QA] bidding site'; status = 'BIDDING' }
    $bidId = [long]$bid.Body.data.id
    $toProgress = Invoke-Api -Service site -Method PATCH -Path "/api/v1/sites/$bidId/status" -Body @{ status = 'IN_PROGRESS' }
    $back = Invoke-Api -Service site -Method PATCH -Path "/api/v1/sites/$bidId/status" -Body @{ status = 'BIDDING' }
    $bad = Invoke-Api -Service site -Method POST -Path '/api/v1/sites' -Body @{ siteName = '[QA] bad'; status = 'SETTLING' }
    Add-Check $p 'BIDDING create -> IN_PROGRESS' ($bid.Status -eq 201 -and $bid.Body.data.status -eq 'BIDDING' -and $toProgress.Status -eq 200 -and $toProgress.Body.data.status -eq 'IN_PROGRESS' -and $back.Status -eq 400 -and $bad.Status -eq 400) "create=$($bid.Status)/$($bid.Body.data.status) toInProgress=$($toProgress.Status) backToBidding=$($back.Status) createSettling=$($bad.Status)"
    $bid2 = Invoke-Api -Service site -Method POST -Path '/api/v1/sites' -Body @{ siteName = '[QA] bidding left'; status = 'BIDDING' }
    $rowsBefore = @(Get-PaymentRows)
    Stop-App tax $p

    # 3) Restart new tax-service: backfill must stay idempotent.
    $p = 'restart'
    $r = Start-App -Service tax -Root $NewRoot -Phase $p
    $rowsAfter = @(Get-PaymentRows)
    Add-Check $p 'restart: no duplicate backfill' ($r.Outcome -eq 'STARTED' -and ($rowsAfter -join ';') -ceq ($rowsBefore -join ';') -and $pay2.Status -eq 201) "start=$($r.Outcome) rows=$($rowsAfter.Count) (before $($rowsBefore.Count))"
    Stop-App tax $p; Stop-App site 'new'

    # 4) Roll back to old code with the widened schema present.
    $p = 'rollback'
    $r = Start-App -Service site -Root $OldRoot -Phase $p; Add-Check $p 'site-service(old) start on widened enum' ($r.Outcome -eq 'STARTED') "$($r.Outcome) column=$(Get-StatusColumn)"
    $r = Start-App -Service tax -Root $OldRoot -Phase $p; Add-Check $p 'tax-service(old) start with tax_payments' ($r.Outcome -eq 'STARTED') $r.Outcome
    $list = Invoke-Api -Service site -Method GET -Path '/api/v1/sites'
    $bidRows = @(Invoke-Sql -Sql "SELECT id FROM buildflow_site.sites WHERE status='BIDDING' ORDER BY id")
    [void](Invoke-Sql -Sql "UPDATE buildflow_site.sites SET status='IN_PROGRESS' WHERE status='BIDDING'")
    $list2 = Invoke-Api -Service site -Method GET -Path '/api/v1/sites'
    Add-Check $p 'BIDDING row breaks old list; runbook lowering fixes it' ($list.Status -ge 500 -and $list2.Status -eq 200) "with BIDDING=$($list.Status) ids=$($bidRows -join ',') -> after UPDATE=$($list2.Status) count=$(@($list2.Body.data).Count)"
    $taxList = Invoke-Api -Service tax -Method GET -Path '/api/v1/taxes'
    $o = Get-Outstanding $s1
    Add-Check $p 'old tax API on new data' ($taxList.Status -eq 200 -and $null -ne $o) "list=$($taxList.Status) outstanding=$($o.outstandingAmount) (old code ignores partial payments)"
    $oldConfirm = Invoke-Api -Service tax -Method PATCH -Path "/api/v1/taxes/$($inv.unpaid)/confirm-payment" -Body @{ paymentDate = $today }
    Add-Check $p 'old confirm-payment on partially paid invoice' ($oldConfirm.Status -eq 200) "status=$($oldConfirm.Status)"
    Stop-App tax $p

    # 5) Re-upgrade after the rollback: invoice confirmed by old code while a partial payment row exists.
    $p = 'reupgrade'
    $r = Start-App -Service tax -Root $NewRoot -Phase $p
    $mixed = (Invoke-Api -Service tax -Method GET -Path "/api/v1/taxes/$($inv.unpaid)").Body.data
    $o = Get-Outstanding $s1
    $consistent = $mixed.paymentConfirmed -and [decimal]$mixed.paidAmount -eq [decimal]$mixed.totalAmount
    # Known risk (WARN): old confirm-payment does not write tax_payments, and the backfill skips invoices
    # that already have rows, so the invoice reads as PAID with paidAmount < totalAmount until the rest is recorded.
    Add-Check $p 'start after rollback' ($r.Outcome -eq 'STARTED') $r.Outcome
    Add-Check -WarnOnly $p 'confirmed-by-old-code invoice consistent' $consistent "start=$($r.Outcome) confirmed=$($mixed.paymentConfirmed) paid=$($mixed.paidAmount)/$($mixed.totalAmount) status=$($mixed.paymentStatus) outstanding=$($o.outstandingAmount)"
    $fix = Invoke-Api -Service tax -Method POST -Path "/api/v1/taxes/$($inv.unpaid)/payments" -Body @{ amount = 800000; paymentDate = $today; memo = 'qa remaining' }
    $fixed = (Invoke-Api -Service tax -Method GET -Path "/api/v1/taxes/$($inv.unpaid)").Body.data
    Add-Check $p 'remaining payment heals mixed state' ($fix.Status -eq 201 -and $fixed.paymentConfirmed -and [decimal]$fixed.paidAmount -eq 1100000) "record=$($fix.Status) confirmed=$($fixed.paymentConfirmed) paid=$($fixed.paidAmount)"
    Stop-App tax $p; Stop-App site 'rollback'

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines((Join-Path $OutputDirectory 'report.txt'), @($results | ForEach-Object { "[$($_.Phase)] $($_.Result) $($_.Check) :: $($_.Evidence)" }), $encoding)
    $failed = @($results | Where-Object { $_.Result -eq 'FAIL' })
    if ($failed.Count -gt 0) { Write-Host "NOT PASSED: $($failed.Count) check(s)"; $script:exitCode = 1 } else { Write-Host 'ALL PASSED'; $script:exitCode = 0 }
}
finally {
    foreach ($name in @($appContainers)) {
        try { Stop-App -Service ($name.Substring($project.Length + 1)) -Phase 'cleanup' } catch { Write-Warning "App container cleanup failed: $name ($($_.Exception.Message))" }
    }
    if ($started) {
        $ids = @(Get-OutputLines -Text (Invoke-Docker -Arguments @('ps', '-aq', '--filter', "label=com.docker.compose.project=$project")))
        $safe = $true
        foreach ($id in $ids) {
            $item = @((Invoke-Docker -Arguments @('inspect', $id) | ConvertFrom-Json))[0]
            if ([string]$item.Config.Labels.$labelKey -cne $nonce -or [string]$item.Config.Labels.'com.docker.compose.service' -cne 'mysql') { $safe = $false }
        }
        if ($safe -and $project -cmatch '^bfupg\d+$') {
            [void](Invoke-NativeProcess -FileName 'docker' -Arguments ($composeArgs + @('down', '--timeout', '10')) -WorkingDirectory $repoRoot)
        } else {
            Write-Warning "Cleanup identity check failed; left project $project untouched."
        }
        $left = @(Get-OutputLines -Text (Invoke-Docker -Arguments @('ps', '-aq', '--filter', "label=$labelKey=$nonce"))) +
            @(Get-OutputLines -Text (Invoke-Docker -Arguments @('network', 'ls', '-q', '--filter', "label=$labelKey=$nonce"))) +
            @(Get-OutputLines -Text (Invoke-Docker -Arguments @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project")))
        if ($left.Count -gt 0) { Write-Warning "Rehearsal resources remain: $($left -join ', ')" } else { Write-Host "Cleanup verified: project $project has no containers, networks or volumes left." }
    }
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $script:exitCode
