[CmdletBinding()]
param(
    # Checkout of the release currently in production with estimate/site boot jars built, e.g.
    #   git archive <prod-sha> | tar -x -C <dir>;  <dir>\gradlew.bat -p <dir> :estimate-service:bootJar :site-service:bootJar -x test
    [Parameter(Mandatory = $true)][string]$OldRoot,
    # Checkout of the candidate release (default: this repository) with estimate/site boot jars built.
    [string]$NewRoot = '',
    [string]$OutputDirectory = '',
    [ValidateRange(60, 900)][int]$StartupTimeoutSeconds = 300,
    [ValidateRange(10, 300)][int]$ConvergeTimeoutSeconds = 90
)

# Isolated upgrade/rollback rehearsal for estimate revisions/additional work (kind, revision_of_id,
# chain_root_id, superseded_by_id, status SUPERSEDED) on the production path (Hibernate ddl-auto update),
# with the real estimate -> outbox -> Kafka -> site profit path:
#   old code builds the schema + synthetic rows -> new code upgrades in place (revision, additional work,
#   replay, order) -> restart -> old code again (rollback; SUPERSEDED/draft REVISION rows held aside per
#   runbook) -> new code again (rows restored, chain still works).
# Touches only a random Compose project (bfupgest<random>) whose resources carry a nonce label.
# Never uses the existing `buildflow` project, host ports, named volumes, `down -v` or force flags.

Set-StrictMode -Off
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Set-StrictMode -Off   # common.ps1 turns strict mode on; empty query results are probed with [0].

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($NewRoot)) { $NewRoot = $repoRoot }
$nonce = [Guid]::NewGuid().ToString('N')
$project = 'bfupgest' + (Get-Random -Minimum 10000 -Maximum 99999)
$labelKey = 'com.buildflow.flyway-rehearsal'
$appImage = 'eclipse-temurin:17-jre-jammy'
$composeArgs = @('compose', '--project-directory', $repoRoot, '--project-name', $project, '-f', (Join-Path $PSScriptRoot 'upgrade-rehearsal-estimate.compose.yml'))
$dbPassword = 'Rh' + [Guid]::NewGuid().ToString('N')
$jwtSecret = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$environmentNames = @('REHEARSAL_DB_PASSWORD', 'REHEARSAL_NONCE')
$previous = @{}
$started = $false
$script:exitCode = 1
$appContainers = New-Object System.Collections.Generic.List[string]
$results = New-Object System.Collections.Generic.List[object]
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-upgrade-estimate-' + $nonce.Substring(0, 12))
}
$ports = @{ estimate = 8082; site = 8083 }

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
        '-e', 'CONFIG_SERVER_URL=http://127.0.0.1:1', '-e', 'KAFKA_BOOTSTRAP_SERVERS=kafka:9092', '-e', 'REDIS_HOST=127.0.0.1',
        '-e', 'OLLAMA_URL=http://127.0.0.1:1',
        $appImage, 'java', '-Xmx384m', '-jar', '/app/app.jar',
        "--spring.datasource.url=$url", '--spring.cloud.config.enabled=false', '--eureka.client.enabled=false',
        # Feign estimate -> site without Eureka.
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
    if ($null -ne $Body) { $arguments += @('-H', 'Content-Type: application/json', '-d', ($Body | ConvertTo-Json -Compress -Depth 6)) }
    $lines = (Invoke-Docker -Arguments $arguments) -split "`n"
    $status = [int]$lines[-1].Trim()
    $text = ($lines[0..($lines.Count - 2)] -join "`n").Trim()
    $parsed = $null
    if ($text.StartsWith('{')) { try { $parsed = $text | ConvertFrom-Json } catch { } }
    return [pscustomobject]@{ Status = $status; Body = $parsed }
}

function New-EstimateBody {
    param([long]$SiteId, [string]$Title, [decimal]$Amount, [string]$Kind = '', [long]$RevisionOfId = 0)
    $body = [ordered]@{ siteId = $SiteId; title = $Title; estimateDate = (Get-Date).ToString('yyyy-MM-dd'); memo = '[QA] synthetic'
        items = @(@{ itemName = '[QA] item'; unit = 'EA'; quantity = 1; unitPrice = $Amount }) }
    if ($Kind) { $body.kind = $Kind }
    if ($RevisionOfId -gt 0) { $body.revisionOfId = $RevisionOfId }
    return $body
}

function Get-Estimate { param([long]$Id) return (Invoke-Api -Service estimate -Method GET -Path "/api/v1/estimates/$Id") }
function Get-ProfitTotal {
    param([long]$SiteId)
    $r = Invoke-Api -Service site -Method GET -Path "/api/v1/sites/$SiteId/profit"
    if ($r.Status -ne 200) { return $null }
    return [decimal]$r.Body.data.totalEstimateAmount
}

function Wait-ProfitTotal {
    param([long]$SiteId, [decimal]$Expected)
    $deadline = (Get-Date).AddSeconds($ConvergeTimeoutSeconds)
    while ($true) {
        $value = Get-ProfitTotal $SiteId
        if ($null -ne $value -and $value -eq $Expected) { return $value }
        if ((Get-Date) -gt $deadline) { return $value }
        Start-Sleep -Seconds 2
    }
}

function Invoke-Kafka {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return Invoke-Docker -Arguments (@('exec', $script:kafkaId) + $Arguments)
}

# Total lag of site-service-group on the estimate topics (-1 if the group has no committed offsets yet).
function Get-EstimateLag {
    $text = Invoke-Kafka -Arguments @('kafka-consumer-groups.sh', '--bootstrap-server', 'localhost:9092', '--describe', '--group', 'site-service-group')
    $sum = 0; $seen = 0
    foreach ($line in (Get-OutputLines -Text $text)) {
        $cols = @($line -split '\s+' | Where-Object { $_ })
        if ($cols.Count -ge 6 -and $cols[1] -like 'estimate.*') {
            $seen++
            if ($cols[5] -match '^\d+$') { $sum += [int]$cols[5] }
        }
    }
    if ($seen -eq 0) { return -1 }
    return $sum
}

function Wait-NoLag {
    $deadline = (Get-Date).AddSeconds($ConvergeTimeoutSeconds)
    while ($true) {
        $lag = Get-EstimateLag
        if ($lag -eq 0) { return 0 }
        if ((Get-Date) -gt $deadline) { return $lag }
        Start-Sleep -Seconds 2
    }
}

# Produces "key|json" lines to a topic with the broker's console producer (file copied in, no host ports).
function Send-KafkaRecords {
    param([string]$Topic, [string[]]$Lines)
    $local = Join-Path $OutputDirectory ("inject-" + [Guid]::NewGuid().ToString('N').Substring(0, 8) + '.txt')
    [IO.File]::WriteAllText($local, (($Lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
    [void](Invoke-Docker -Arguments @('cp', $local, "${script:kafkaId}:/tmp/inject.txt"))
    [void](Invoke-Kafka -Arguments @('bash', '-c', "kafka-console-producer.sh --bootstrap-server localhost:9092 --topic $Topic --property parse.key=true --property key.separator='|' < /tmp/inject.txt"))
}

function New-EventLine {
    param([string]$Type, [long]$EstimateId, [long]$SiteId, [decimal]$Amount)
    $event = [ordered]@{ eventId = [Guid]::NewGuid().ToString(); eventType = $Type; timestamp = (Get-Date).ToString('yyyy-MM-ddTHH:mm:ss')
        payload = [ordered]@{ estimateId = $EstimateId; siteId = $SiteId; totalAmount = $Amount } }
    return "$EstimateId|" + ($event | ConvertTo-Json -Compress -Depth 4)
}

function Get-ColumnType {
    param([string]$Column)
    return @(Invoke-Sql -Sql "SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='buildflow_estimate' AND TABLE_NAME='estimates' AND COLUMN_NAME='$Column'")[0]
}
function Get-ProcessedCount { return [int](@(Invoke-Sql -Sql 'SELECT COUNT(*) FROM buildflow_site.processed_profit_events')[0]) }

try {
    foreach ($svc in @('estimate', 'site')) { [void](Get-JarPath -Root $OldRoot -Service $svc); [void](Get-JarPath -Root $NewRoot -Service $svc) }
    if ((Get-FileHash (Get-JarPath -Root $OldRoot -Service estimate)).Hash -eq (Get-FileHash (Get-JarPath -Root $NewRoot -Service estimate)).Hash) { throw 'Old and new estimate-service jars are identical.' }
    [void](New-Item -ItemType Directory -Force -Path $OutputDirectory)
    foreach ($name in $environmentNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    [Environment]::SetEnvironmentVariable('REHEARSAL_DB_PASSWORD', $dbPassword, 'Process')
    [Environment]::SetEnvironmentVariable('REHEARSAL_NONCE', $nonce, 'Process')

    Write-Host "Rehearsal project: $project (label $labelKey=$($nonce.Substring(0, 12))...), output: $OutputDirectory"
    $started = $true
    [void](Invoke-NativeProcess -FileName 'docker' -Arguments ($composeArgs + @('up', '-d', '--wait', 'mysql', 'kafka')) -WorkingDirectory $repoRoot)
    foreach ($svc in @('mysql', 'kafka')) {
        $ids = @(Get-OutputLines -Text (Invoke-Docker -Arguments ($composeArgs + @('ps', '-q', $svc))))
        if ($ids.Count -ne 1) { throw "Rehearsal $svc container not found." }
        $item = @((Invoke-Docker -Arguments @('inspect', $ids[0]) | ConvertFrom-Json))[0]
        if ([string]$item.Config.Labels.$labelKey -cne $nonce -or [string]$item.Config.Labels.'com.docker.compose.project' -cne $project) { throw "Rehearsal $svc identity check failed." }
        if ($svc -eq 'mysql') { $script:mysqlId = $ids[0] } else { $script:kafkaId = $ids[0] }
    }
    foreach ($schema in @('buildflow_estimate', 'buildflow_site')) { [void](Invoke-Sql -Sql "CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci") }
    foreach ($topic in @('estimate.parsed', 'estimate.deleted')) {
        [void](Invoke-Kafka -Arguments @('kafka-topics.sh', '--bootstrap-server', 'localhost:9092', '--create', '--if-not-exists', '--topic', $topic, '--partitions', '3', '--replication-factor', '1'))
    }

    # 1) Old code: production-shaped schema + synthetic rows through the old API and the real Kafka path.
    $p = 'old'
    $r = Start-App -Service site -Root $OldRoot -Phase $p; Add-Check $p 'site-service(old) ddl-auto start' ($r.Outcome -eq 'STARTED') $r.Outcome
    $r = Start-App -Service estimate -Root $OldRoot -Phase $p; Add-Check $p 'estimate-service(old) ddl-auto start' ($r.Outcome -eq 'STARTED') $r.Outcome
    $s1 = [long](Invoke-Api -Service site -Method POST -Path '/api/v1/sites' -Body @{ siteName = '[QA] synthetic site 1' }).Body.data.id
    $s2 = [long](Invoke-Api -Service site -Method POST -Path '/api/v1/sites' -Body @{ siteName = '[QA] synthetic site 2' }).Body.data.id
    $e1 = [long](Invoke-Api -Service estimate -Method POST -Path '/api/v1/estimates' -Body (New-EstimateBody $s1 '[QA] original' 1000000)).Body.data.id
    $c1 = Invoke-Api -Service estimate -Method PATCH -Path "/api/v1/estimates/$e1/confirm"
    $e2 = [long](Invoke-Api -Service estimate -Method POST -Path '/api/v1/estimates' -Body (New-EstimateBody $s1 '[QA] draft' 500000)).Body.data.id
    $total = Wait-ProfitTotal $s1 1000000
    Add-Check $p 'synthetic rows + profit via Kafka' ($e1 -gt 0 -and $e2 -gt 0 -and $c1.Status -eq 200 -and $total -eq 1000000) "sites=$s1,$s2 confirmed=$e1 draft=$e2 confirm=$($c1.Status) profit=$total"
    $statusType = Get-ColumnType 'status'
    $kindType = Get-ColumnType 'kind'
    Add-Check $p 'old schema shape' ($statusType -notmatch 'SUPERSEDED' -and [string]::IsNullOrEmpty($kindType)) "status=$statusType kind=$(if ($kindType) { $kindType } else { '(none)' })"
    Stop-App estimate $p; Stop-App site $p

    # 2) New code on the same schema (ddl-auto update).
    $p = 'new'
    $r = Start-App -Service site -Root $NewRoot -Phase $p; Add-Check $p 'site-service(new) ddl-auto start' ($r.Outcome -eq 'STARTED') $r.Outcome
    $r = Start-App -Service estimate -Root $NewRoot -Phase $p; Add-Check $p 'estimate-service(new) ddl-auto start' ($r.Outcome -eq 'STARTED') $r.Outcome
    $cols = @(Invoke-Sql -Sql "SELECT CONCAT(COLUMN_NAME,':',COLUMN_TYPE,':',IS_NULLABLE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='buildflow_estimate' AND TABLE_NAME='estimates' AND COLUMN_NAME IN ('kind','revision_of_id','chain_root_id','superseded_by_id','status') ORDER BY COLUMN_NAME")
    $index = @(Invoke-Sql -Sql "SELECT CONCAT(INDEX_NAME,':',COLUMN_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='buildflow_estimate' AND TABLE_NAME='estimates' AND INDEX_NAME='idx_estimates_chain_root'")
    $joined = $cols -join ' '
    $ddl = (@(Invoke-Sql -Sql 'SHOW CREATE TABLE buildflow_estimate.estimates')[0] -split "`t", 2)[1] -replace '\\n', "`n"
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'estimates-after-upgrade.sql'), $ddl, (New-Object System.Text.UTF8Encoding($false)))
    $statusDdl = @($ddl -split "`n" | Where-Object { $_ -match '^\s*`status`' })[0]
    Add-Check $p 'SHOW CREATE status widened' ($statusDdl -match "enum\('CONFIRMED','DRAFT','SUPERSEDED'\)") ([string]$statusDdl).Trim()
    Add-Check $p 'columns + SUPERSEDED enum + index' ($cols.Count -eq 5 -and $joined -match "kind:enum\('ADDITIONAL','ORIGINAL','REVISION'\):YES" -and $joined -match 'revision_of_id:bigint:YES' -and $joined -match 'chain_root_id:bigint:YES' -and $joined -match 'superseded_by_id:bigint:YES' -and $joined -match "status:enum\('CONFIRMED','DRAFT','SUPERSEDED'\):NO" -and ($index -join ',') -eq 'idx_estimates_chain_root:chain_root_id') "$joined | index=$($index -join ',')"
    $rawKind = @(Invoke-Sql -Sql "SELECT IFNULL(kind,'NULL') FROM buildflow_estimate.estimates WHERE id=$e1")[0]
    $g1 = (Get-Estimate $e1).Body.data
    $list = Invoke-Api -Service estimate -Method GET -Path "/api/v1/estimates?siteId=$s1"
    Add-Check $p 'legacy row kind NULL -> ORIGINAL' ($rawKind -eq 'NULL' -and $g1.kind -eq 'ORIGINAL' -and [long]$g1.chainRootId -eq $e1 -and $g1.status -eq 'CONFIRMED' -and $list.Status -eq 200) "db=$rawKind api=$($g1.kind) chainRoot=$($g1.chainRootId) status=$($g1.status) list=$($list.Status)"

    $badDraft = Invoke-Api -Service estimate -Method POST -Path '/api/v1/estimates' -Body (New-EstimateBody $s1 '[QA] revision of draft' 1 'REVISION' $e2)
    $badSite = Invoke-Api -Service estimate -Method POST -Path '/api/v1/estimates' -Body (New-EstimateBody $s2 '[QA] revision other site' 1 'REVISION' $e1)
    Add-Check $p 'invalid relation rejected' ($badDraft.Status -eq 400 -and $badSite.Status -eq 400) "revisionOf draft=$($badDraft.Status) other site=$($badSite.Status)"

    $rev = Invoke-Api -Service estimate -Method POST -Path '/api/v1/estimates' -Body (New-EstimateBody $s1 '[QA] revision' 1200000 'REVISION' $e1)
    $e3 = [long]$rev.Body.data.id
    $total = Get-ProfitTotal $s1
    $c3 = Invoke-Api -Service estimate -Method PATCH -Path "/api/v1/estimates/$e3/confirm"
    $g1 = (Get-Estimate $e1).Body.data
    $converged = Wait-ProfitTotal $s1 1200000
    Add-Check $p 'revision 1200k confirm -> original SUPERSEDED, profit 1200k' ($rev.Status -eq 201 -and $total -eq 1000000 -and $c3.Status -eq 200 -and $g1.status -eq 'SUPERSEDED' -and [long]$g1.supersededById -eq $e3 -and [long]$rev.Body.data.chainRootId -eq $e1 -and $converged -eq 1200000) "create=$($rev.Status) draftProfit=$total confirm=$($c3.Status) e1=$($g1.status)/by=$($g1.supersededById) chainRoot=$($rev.Body.data.chainRootId) profit=$converged"
    $outbox = @(Invoke-Sql -Sql "SELECT CONCAT(topic,'|',record_key,'|',status) FROM buildflow_estimate.outbox_events ORDER BY created_at, event_id")
    Add-Check $p 'outbox events SENT' (@($outbox | Where-Object { $_ -notlike '*|SENT' }).Count -eq 0 -and $outbox.Count -ge 3) ($outbox -join '; ')

    $put = Invoke-Api -Service estimate -Method PUT -Path "/api/v1/estimates/$e1" -Body ([ordered]@{ title = '[QA] edit superseded'; estimateDate = (Get-Date).ToString('yyyy-MM-dd'); items = @(@{ itemName = 'x'; unit = 'EA'; quantity = 1; unitPrice = 1 }) })
    $reconfirm = Invoke-Api -Service estimate -Method PATCH -Path "/api/v1/estimates/$e1/confirm"
    $del = Invoke-Api -Service estimate -Method DELETE -Path "/api/v1/estimates/$e1"
    Add-Check $p 'superseded estimate edit/confirm/delete 409' ($put.Status -eq 409 -and $reconfirm.Status -eq 409 -and $del.Status -eq 409) "put=$($put.Status) confirm=$($reconfirm.Status) delete=$($del.Status)"

    $add = Invoke-Api -Service estimate -Method POST -Path '/api/v1/estimates' -Body (New-EstimateBody $s1 '[QA] additional' 300000 'ADDITIONAL' $e3)
    $e4 = [long]$add.Body.data.id
    $c4 = Invoke-Api -Service estimate -Method PATCH -Path "/api/v1/estimates/$e4/confirm"
    $converged = Wait-ProfitTotal $s1 1500000
    $g3 = (Get-Estimate $e3).Body.data
    Add-Check $p 'additional 300k confirm -> profit 1500k' ($add.Status -eq 201 -and $c4.Status -eq 200 -and $converged -eq 1500000 -and $g3.status -eq 'CONFIRMED' -and $add.Body.data.chainRootId -eq $e4) "create=$($add.Status) confirm=$($c4.Status) profit=$converged revision=$($g3.status) addChainRoot=$($add.Body.data.chainRootId)"

    # Same-eventId replay of the revision's estimate.parsed and the original's SUPERSEDED estimate.deleted.
    [void](Wait-NoLag)
    $processedBefore = Get-ProcessedCount
    $parsedJson = @(Invoke-Sql -Sql "SELECT payload_json FROM buildflow_estimate.outbox_events WHERE topic='estimate.parsed' AND record_key='$e3'")
    $deletedJson = @(Invoke-Sql -Sql "SELECT payload_json FROM buildflow_estimate.outbox_events WHERE topic='estimate.deleted' AND record_key='$e1'")
    Add-Check $p 'SUPERSEDED deleted payload' ($deletedJson.Count -eq 1 -and $deletedJson[0] -match '"reason":"SUPERSEDED"' -and $deletedJson[0] -match ('"supersededById":' + $e3)) ($deletedJson -join ' ')
    Send-KafkaRecords 'estimate.parsed' @("$e3|$($parsedJson[0])", "$e3|$($parsedJson[0])")
    Send-KafkaRecords 'estimate.deleted' @("$e1|$($deletedJson[0])")
    $lag = Wait-NoLag
    $total = Get-ProfitTotal $s1
    $processedAfter = Get-ProcessedCount
    Add-Check $p 'replay same eventId -> no double apply' ($lag -eq 0 -and $total -eq 1500000 -and $processedAfter -eq $processedBefore) "replayed=3 lag=$lag profit=$total processed=$processedBefore->$processedAfter"

    # Order independence on site 2: superseding delete before both parsed events (negative intermediate).
    $before2 = Get-ProfitTotal $s2
    Send-KafkaRecords 'estimate.deleted' @((New-EventLine 'ESTIMATE_DELETED' 9001 $s2 1000000))
    $mid = Wait-ProfitTotal $s2 (-1000000)
    Send-KafkaRecords 'estimate.parsed' @((New-EventLine 'ESTIMATE_PARSED' 9002 $s2 1200000), (New-EventLine 'ESTIMATE_PARSED' 9001 $s2 1000000))
    $final2 = Wait-ProfitTotal $s2 1200000
    Add-Check $p 'order independence (deleted before parsed)' ($final2 -eq 1200000) "site2 start=$before2 afterDelete=$mid final=$final2"

    # Leave a draft REVISION in the chain for the rollback scenario.
    $draftRev = Invoke-Api -Service estimate -Method POST -Path '/api/v1/estimates' -Body (New-EstimateBody $s1 '[QA] revision draft' 1300000 'REVISION' $e3)
    $e5 = [long]$draftRev.Body.data.id
    Add-Check $p 'draft revision for rollback' ($draftRev.Status -eq 201 -and [long]$draftRev.Body.data.chainRootId -eq $e1) "id=$e5 chainRoot=$($draftRev.Body.data.chainRootId)"
    Stop-App estimate $p

    # 3) Restart new estimate-service: ddl-auto idempotent, data intact.
    $p = 'restart'
    $r = Start-App -Service estimate -Root $NewRoot -Phase $p
    $list = Invoke-Api -Service estimate -Method GET -Path "/api/v1/estimates?siteId=$s1"
    $states = (@($list.Body.data) | Sort-Object id | ForEach-Object { "$($_.id):$($_.kind)/$($_.status)" }) -join ','
    Add-Check $p 'restart: list intact' ($r.Outcome -eq 'STARTED' -and $list.Status -eq 200 -and @($list.Body.data).Count -eq 5) "start=$($r.Outcome) $states"
    Stop-App estimate $p

    # 4) Roll back estimate-service to old code with SUPERSEDED/REVISION rows present.
    $p = 'rollback'
    $r = Start-App -Service estimate -Root $OldRoot -Phase $p
    Add-Check $p 'estimate-service(old) start on widened schema' ($r.Outcome -eq 'STARTED') "$($r.Outcome) status=$(Get-ColumnType 'status')"
    $listAll = Invoke-Api -Service estimate -Method GET -Path '/api/v1/estimates'
    $listSite = Invoke-Api -Service estimate -Method GET -Path "/api/v1/estimates?siteId=$s1"
    $getSup = Get-Estimate $e1
    $getRev = Get-Estimate $e3
    Add-Check -WarnOnly $p 'old code reads SUPERSEDED rows' ($listAll.Status -eq 200 -and $getSup.Status -eq 200) "list=$($listAll.Status) siteList=$($listSite.Status) getSuperseded=$($getSup.Status) getConfirmedRevision=$($getRev.Status)"
    # Runbook: hold SUPERSEDED rows and draft REVISION rows (+ items) aside while old code runs.
    $hold = @(
        'CREATE TABLE buildflow_estimate.rollback_hold_estimates LIKE buildflow_estimate.estimates',
        'CREATE TABLE buildflow_estimate.rollback_hold_estimate_items LIKE buildflow_estimate.estimate_items',
        "INSERT INTO buildflow_estimate.rollback_hold_estimates SELECT * FROM buildflow_estimate.estimates WHERE status='SUPERSEDED' OR (kind='REVISION' AND status='DRAFT')",
        'INSERT INTO buildflow_estimate.rollback_hold_estimate_items SELECT i.* FROM buildflow_estimate.estimate_items i JOIN buildflow_estimate.rollback_hold_estimates h ON h.id = i.estimate_id',
        'DELETE i FROM buildflow_estimate.estimate_items i JOIN buildflow_estimate.rollback_hold_estimates h ON h.id = i.estimate_id',
        'DELETE e FROM buildflow_estimate.estimates e JOIN buildflow_estimate.rollback_hold_estimates h ON h.id = e.id'
    ) -join '; '
    [void](Invoke-Sql -Sql "USE buildflow_estimate; START TRANSACTION; $hold; COMMIT")
    $held = @(Invoke-Sql -Sql 'SELECT id FROM buildflow_estimate.rollback_hold_estimates ORDER BY id') -join ','
    $listAll2 = Invoke-Api -Service estimate -Method GET -Path '/api/v1/estimates'
    $total = Get-ProfitTotal $s1
    Add-Check $p 'hold-aside procedure restores old list' ($listAll2.Status -eq 200 -and $held -eq "$e1,$e5" -and $total -eq 1500000) "held=$held list=$($listAll2.Status) count=$(@($listAll2.Body.data).Count) profit=$total"
    $oldDraft = Invoke-Api -Service estimate -Method POST -Path '/api/v1/estimates' -Body (New-EstimateBody $s1 '[QA] draft during rollback' 10000)
    $oldEdit = Invoke-Api -Service estimate -Method PUT -Path "/api/v1/estimates/$e2" -Body ([ordered]@{ title = '[QA] draft edited in rollback'; estimateDate = (Get-Date).ToString('yyyy-MM-dd'); items = @(@{ itemName = 'x'; unit = 'EA'; quantity = 1; unitPrice = 500000 }) })
    Add-Check $p 'old API write during rollback' ($oldDraft.Status -eq 201 -and $oldEdit.Status -eq 200) "create=$($oldDraft.Status) editDraft=$($oldEdit.Status)"
    $e6 = [long]$oldDraft.Body.data.id
    Stop-App estimate $p

    # 5) Re-upgrade: restore held rows, then new code again.
    $p = 'reupgrade'
    $restore = @(
        'INSERT INTO buildflow_estimate.estimates SELECT * FROM buildflow_estimate.rollback_hold_estimates',
        'INSERT INTO buildflow_estimate.estimate_items SELECT * FROM buildflow_estimate.rollback_hold_estimate_items',
        'DROP TABLE buildflow_estimate.rollback_hold_estimate_items',
        'DROP TABLE buildflow_estimate.rollback_hold_estimates'
    ) -join '; '
    [void](Invoke-Sql -Sql "USE buildflow_estimate; START TRANSACTION; $restore; COMMIT")
    $r = Start-App -Service estimate -Root $NewRoot -Phase $p
    $list = Invoke-Api -Service estimate -Method GET -Path "/api/v1/estimates?siteId=$s1"
    $states = (@($list.Body.data) | Sort-Object id | ForEach-Object { "$($_.id):$($_.kind)/$($_.status)" }) -join ','
    $g6 = (Get-Estimate $e6).Body.data
    Add-Check $p 'restored rows + rollback-era row readable' ($r.Outcome -eq 'STARTED' -and $list.Status -eq 200 -and @($list.Body.data).Count -eq 6 -and $g6.kind -eq 'ORIGINAL') "start=$($r.Outcome) $states"
    $c5 = Invoke-Api -Service estimate -Method PATCH -Path "/api/v1/estimates/$e5/confirm"
    $converged = Wait-ProfitTotal $s1 1600000
    $g3 = (Get-Estimate $e3).Body.data
    Add-Check $p 'confirm restored draft revision 1300k -> profit 1600k' ($c5.Status -eq 200 -and $g3.status -eq 'SUPERSEDED' -and [long]$g3.supersededById -eq $e5 -and $converged -eq 1600000) "confirm=$($c5.Status) e3=$($g3.status)/by=$($g3.supersededById) profit=$converged"
    Stop-App estimate $p; Stop-App site 'new'

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines((Join-Path $OutputDirectory 'report.txt'), @($results | ForEach-Object { "[$($_.Phase)] $($_.Result) $($_.Check) :: $($_.Evidence)" }), $encoding)
    $failed = @($results | Where-Object { $_.Result -eq 'FAIL' })
    if ($failed.Count -gt 0) { Write-Host "NOT PASSED: $($failed.Count) check(s)"; $script:exitCode = 1 } else { Write-Host 'ALL PASSED'; $script:exitCode = 0 }
}
catch {
    Write-Host "ERROR: $($_.Exception.Message) at line $($_.InvocationInfo.ScriptLineNumber)"
    $script:exitCode = 1
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
            if ([string]$item.Config.Labels.$labelKey -cne $nonce -or @('mysql', 'kafka') -notcontains [string]$item.Config.Labels.'com.docker.compose.service') { $safe = $false }
        }
        if ($safe -and $project -cmatch '^bfupgest\d+$') {
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
