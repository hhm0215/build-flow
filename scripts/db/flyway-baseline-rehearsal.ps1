[CmdletBinding()]
param(
    [string[]]$Services = @('auth', 'chat', 'estimate', 'purchase', 'tax', 'site', 'notification'),
    [string]$OutputDirectory = '',
    [ValidateRange(1, 7)][int]$MaxParallel = 3,
    [ValidateRange(60, 900)][int]$StartupTimeoutSeconds = 300,
    [switch]$BuildJars
)

# Isolated rehearsal: does a schema created the production way (Hibernate ddl-auto update,
# auth = schema.sql) match the opt-in Flyway V1, and does a one-time baseline(version=1)
# followed by migrate(0) + Hibernate validate work on it?
# Touches only a random Compose project (bfbase<random>) whose resources carry a nonce label.
# Never uses the existing `buildflow` project, host ports, named volumes, `down -v` or force flags.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$nonce = [Guid]::NewGuid().ToString('N')
$project = 'bfbase' + (Get-Random -Minimum 10000 -Maximum 99999)
$labelKey = 'com.buildflow.flyway-rehearsal'
$appImage = 'eclipse-temurin:17-jre-jammy'
$composeArgs = @('compose', '--project-directory', $repoRoot, '--project-name', $project, '-f', (Join-Path $PSScriptRoot 'flyway-baseline-rehearsal.compose.yml'))
$dbPassword = 'Rh' + [Guid]::NewGuid().ToString('N')
$jwtSecret = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$environmentNames = @('REHEARSAL_DB_PASSWORD', 'REHEARSAL_NONCE')
$previous = @{}
$started = $false
$script:exitCode = 1
$script:explicitCharset = @{}
$appContainers = New-Object System.Collections.Generic.List[string]
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path ([IO.Path]::GetTempPath()) ('buildflow-flyway-rehearsal-' + $nonce.Substring(0, 12))
}

foreach ($service in $Services) {
    if ($service -notin @('auth', 'chat', 'estimate', 'purchase', 'tax', 'site', 'notification')) { throw "Unknown service: $service" }
}

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return (Invoke-NativeProcess -FileName 'docker' -Arguments $Arguments -WorkingDirectory $repoRoot).StandardOutput
}

function Get-JarPath {
    param([Parameter(Mandatory = $true)][string]$Service)
    $dir = Join-Path $repoRoot "$Service-service\build\libs"
    $jar = @(Get-ChildItem -Path $dir -Filter "$Service-service-*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*-plain.jar' })
    if ($jar.Count -ne 1) { throw "Expected exactly one boot jar in $dir (run with -BuildJars)." }
    return $jar[0].FullName
}

function Invoke-Sql {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $text = Invoke-Docker -Arguments @('exec', '-e', "MYSQL_PWD=$dbPassword", $script:mysqlId, 'mysql', '--user=root', '--batch', '--skip-column-names', '--default-character-set=utf8mb4', "--execute=$Sql")
    return @(Get-OutputLines -Text $text)
}

function Get-MySqlContainerId {
    $ids = @(Get-OutputLines -Text (Invoke-Docker -Arguments ($composeArgs + @('ps', '-q', 'mysql'))))
    if ($ids.Count -ne 1) { throw 'Rehearsal MySQL container not found.' }
    $item = @((Invoke-Docker -Arguments @('inspect', $ids[0]) | ConvertFrom-Json))[0]
    if ([string]$item.Config.Labels.$labelKey -cne $nonce -or [string]$item.Config.Labels.'com.docker.compose.project' -cne $project) {
        throw 'Rehearsal MySQL identity check failed.'
    }
    if ($null -ne $item.NetworkSettings.Ports -and @($item.NetworkSettings.Ports.PSObject.Properties | Where-Object { $null -ne $_.Value }).Count -ne 0) {
        throw 'Rehearsal MySQL must not publish host ports.'
    }
    return $ids[0]
}

function Start-ServiceRun {
    param(
        [Parameter(Mandatory = $true)][string]$Service,
        [Parameter(Mandatory = $true)][string]$Schema,
        [Parameter(Mandatory = $true)][string]$Phase,
        [string[]]$ExtraArguments = @()
    )
    Assert-MySqlIdentifier -Value $Schema
    $name = "$project-$Service-$Phase"
    $url = "jdbc:mysql://mysql:3306/$Schema" + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=UTF-8'
    $arguments = @(
        'run', '-d', '--name', $name, '--network', "${project}_default",
        '--label', "$labelKey=$nonce", '--label', "$labelKey.project=$project",
        '--mount', ('type=bind,source=' + (Get-JarPath -Service $Service) + ',target=/app/app.jar,readonly'),
        '-e', "DB_PASSWORD=$dbPassword", '-e', "JWT_SECRET=$jwtSecret", '-e', 'TZ=Asia/Seoul',
        '-e', 'CONFIG_SERVER_URL=http://127.0.0.1:1', '-e', 'KAFKA_BOOTSTRAP_SERVERS=mysql:9', '-e', 'REDIS_HOST=127.0.0.1',
        $appImage, 'java', '-Xmx384m', '-jar', '/app/app.jar',
        "--spring.datasource.url=$url", '--spring.cloud.config.enabled=false', '--eureka.client.enabled=false',
        '--spring.kafka.admin.auto-create=false', '--spring.kafka.listener.auto-startup=false'
    ) + $ExtraArguments
    [void](Invoke-Docker -Arguments $arguments)
    $appContainers.Add($name)
    return $name
}

function Stop-ServiceRun {
    param([Parameter(Mandatory = $true)][string]$Name)
    $item = @((Invoke-Docker -Arguments @('inspect', $Name) | ConvertFrom-Json))[0]
    if ([string]$item.Config.Labels.$labelKey -cne $nonce -or [string]$item.Config.Labels."$labelKey.project" -cne $project) {
        throw "Refusing to remove unlabelled container: $Name"
    }
    if ($item.State.Running) { [void](Invoke-Docker -Arguments @('stop', '--time', '10', $Name)) }
    [void](Invoke-Docker -Arguments @('rm', $Name))
    [void]$appContainers.Remove($Name)
}

function Wait-ServiceRuns {
    param([Parameter(Mandatory = $true)][hashtable]$Runs)  # service -> container name
    $result = @{}
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ($result.Count -lt $Runs.Count) {
        foreach ($service in @($Runs.Keys)) {
            if ($result.ContainsKey($service)) { continue }
            $name = $Runs[$service]
            $proc = Invoke-NativeProcess -FileName 'docker' -Arguments @('logs', $name) -WorkingDirectory $repoRoot
            $log = $proc.StandardOutput + "`n" + $proc.StandardError
            $state = (Invoke-Docker -Arguments @('inspect', '--format', '{{.State.Running}}', $name)).Trim()
            $outcome = $null
            if ($log -match 'Started \S+Application in') { $outcome = 'STARTED' }
            elseif ($state -ne 'true') { $outcome = 'EXITED' }
            elseif ((Get-Date) -gt $deadline) { $outcome = 'TIMEOUT' }
            if ($null -ne $outcome) {
                $flyway = @($log -split "`r?`n" | Where-Object { $_ -match 'o\.f\.|org\.flywaydb|flyway' -and $_ -match 'Successfully|up to date|baseline|Migrating|Validated|Current version' } |
                    ForEach-Object { ($_ -replace '^.*?(DbMigrate|DbBaseline|DbValidate|BaseDatabaseType|JdbcTableSchemaHistory|DbSchemas)\s*:\s*', '$1: ').Trim() })
                $errors = @($log -split "`r?`n" | Where-Object { $_ -match 'APPLICATION FAILED|Schema-validation|SchemaManagementException|FlywayException|Caused by' } | Select-Object -First 8)
                [IO.File]::WriteAllText((Join-Path $OutputDirectory "$name.log"), $log, (New-Object System.Text.UTF8Encoding($false)))
                $result[$service] = [pscustomobject]@{ Outcome = $outcome; Flyway = $flyway; Errors = $errors }
                Stop-ServiceRun -Name $name
            }
        }
        if ($result.Count -lt $Runs.Count) { Start-Sleep -Seconds 3 }
    }
    return $result
}

function Invoke-Phase {
    param(
        [Parameter(Mandatory = $true)][string[]]$PhaseServices,
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][scriptblock]$ArgumentsFor  # service -> @{ Schema; Extra }
    )
    $all = @{}
    for ($i = 0; $i -lt $PhaseServices.Count; $i += $MaxParallel) {
        $batch = @{}
        foreach ($service in @($PhaseServices[$i..([Math]::Min($i + $MaxParallel, $PhaseServices.Count) - 1)])) {
            $spec = & $ArgumentsFor $service
            $batch[$service] = Start-ServiceRun -Service $service -Schema $spec.Schema -Phase $Phase -ExtraArguments $spec.Extra
        }
        $done = Wait-ServiceRuns -Runs $batch
        foreach ($key in $done.Keys) { $all[$key] = $done[$key] }
    }
    return $all
}

function Get-NormalizedCreateTables {
    param([Parameter(Mandatory = $true)][string]$Schema)
    $tables = Invoke-Sql -Sql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Schema' AND TABLE_NAME<>'flyway_schema_history' ORDER BY TABLE_NAME"
    $map = [ordered]@{}
    foreach ($table in $tables) {
        Assert-MySqlIdentifier -Value $table
        $row = @(Invoke-Sql -Sql "SHOW CREATE TABLE ``$Schema``.``$table``")[0]
        $ddl = ($row -split "`t", 2)[1] -replace ' AUTO_INCREMENT=\d+', ''
        # Columns created from SQL with an explicit COLLATE are printed with a redundant
        # "CHARACTER SET utf8mb4" (data-dictionary notation only); information_schema facts
        # are compared separately, so normalize this notation and count it.
        $script:explicitCharset[$Schema] = [int]$script:explicitCharset[$Schema] + ([regex]::Matches($ddl, ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')).Count
        $ddl = $ddl -replace ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci', ' COLLATE utf8mb4_unicode_ci'
        $map[$table] = @($ddl -split '\\n' | ForEach-Object { $_.Trim().TrimEnd(',') })
    }
    return $map
}

function Get-SchemaFacts {
    param([Parameter(Mandatory = $true)][string]$Schema)
    $f = "TABLE_SCHEMA='$Schema' AND TABLE_NAME<>'flyway_schema_history'"
    $fk = "k.TABLE_SCHEMA='$Schema' AND k.TABLE_NAME<>'flyway_schema_history'"
    $ft = "t.TABLE_SCHEMA='$Schema' AND t.TABLE_NAME<>'flyway_schema_history'"
    $facts = @()
    $facts += Invoke-Sql -Sql "SELECT CONCAT_WS('|','TABLE',TABLE_NAME,ENGINE,TABLE_COLLATION) FROM information_schema.TABLES WHERE $f"
    $facts += Invoke-Sql -Sql "SELECT CONCAT_WS('|','COLUMN',TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,IFNULL(COLUMN_DEFAULT,'<null>'),EXTRA,IFNULL(COLLATION_NAME,'-')) FROM information_schema.COLUMNS WHERE $f"
    $facts += Invoke-Sql -Sql "SELECT CONCAT_WS('|','INDEX',TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME) FROM information_schema.STATISTICS WHERE $f"
    $facts += Invoke-Sql -Sql "SELECT CONCAT_WS('|','FK',k.TABLE_NAME,k.CONSTRAINT_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,r.UPDATE_RULE,r.DELETE_RULE) FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.TABLE_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME WHERE $fk"
    $facts += Invoke-Sql -Sql "SELECT CONCAT_WS('|','CHECK',t.TABLE_NAME,c.CONSTRAINT_NAME,c.CHECK_CLAUSE) FROM information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS c ON c.CONSTRAINT_SCHEMA=t.TABLE_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME WHERE $ft AND t.CONSTRAINT_TYPE='CHECK'"
    $order = Invoke-Sql -Sql "SELECT CONCAT_WS('|','ORDER',TABLE_NAME,ORDINAL_POSITION,COLUMN_NAME) FROM information_schema.COLUMNS WHERE $f"
    return [pscustomobject]@{ Facts = @($facts | Sort-Object -Unique); Order = @($order | Sort-Object -Unique) }
}

function Compare-Lines {
    param([string[]]$Left = @(), [string[]]$Right = @())
    $onlyLeft = @($Left | Where-Object { $_ -cnotin $Right })
    $onlyRight = @($Right | Where-Object { $_ -cnotin $Left })
    return [pscustomobject]@{ OnlyLeft = $onlyLeft; OnlyRight = $onlyRight }
}

function Get-HistoryRows {
    param([Parameter(Mandatory = $true)][string]$Schema)
    $exists = Invoke-Sql -Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$Schema' AND TABLE_NAME='flyway_schema_history'"
    if ($exists[0] -ne '1') { return @() }
    return @(Invoke-Sql -Sql "SELECT CONCAT_WS('|',installed_rank,IFNULL(version,'-'),type,success) FROM ``$Schema``.flyway_schema_history ORDER BY installed_rank")
}

# Expected history after baseline(1)+migrate: V1 is BASELINE (legacy) or SQL (fresh); later V2.. are SQL in both.
function Get-ExpectedHistory {
    param([Parameter(Mandatory = $true)][string]$Service, [Parameter(Mandatory = $true)][string]$FirstType)
    $dir = Join-Path $repoRoot "$Service-service\src\main\resources\db\migration"
    $versions = @(Get-ChildItem -Path $dir -Filter 'V*__*.sql' | ForEach-Object { [int]($_.Name -replace '^V(\d+)__.*$', '$1') } | Sort-Object)
    $rows = @(); $rank = 0
    foreach ($v in $versions) { $rank++; $rows += "$rank|$v|$(if ($v -eq 1) { $FirstType } else { 'SQL' })|1" }
    return ($rows -join ';')
}

$report = New-Object System.Collections.Generic.List[string]
function Add-Report { param([string]$Line) $report.Add($Line); Write-Host $Line }

try {
    if ($BuildJars) {
        $tasks = @($Services | ForEach-Object { ":$_-service:bootJar" })
        $gradle = if ($env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'gradlew.bat' } else { Join-Path $repoRoot 'gradlew' }
        [void](Invoke-NativeProcess -FileName $gradle -Arguments ($tasks + @('-x', 'test', '--console=plain', '-q')) -WorkingDirectory $repoRoot)
    }
    foreach ($service in $Services) { [void](Get-JarPath -Service $service) }
    [void](New-Item -ItemType Directory -Force -Path $OutputDirectory)

    foreach ($name in $environmentNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    [Environment]::SetEnvironmentVariable('REHEARSAL_DB_PASSWORD', $dbPassword, 'Process')
    [Environment]::SetEnvironmentVariable('REHEARSAL_NONCE', $nonce, 'Process')

    Add-Report "Rehearsal project: $project (label $labelKey=$($nonce.Substring(0, 12))...), output: $OutputDirectory"
    $started = $true
    [void](Invoke-NativeProcess -FileName 'docker' -Arguments ($composeArgs + @('up', '-d', '--wait', 'mysql')) -WorkingDirectory $repoRoot)
    $script:mysqlId = Get-MySqlContainerId
    Add-Report ('MySQL: ' + @(Invoke-Sql -Sql 'SELECT VERSION()')[0])

    foreach ($service in $Services) {
        foreach ($suffix in @('legacy', 'v1')) {
            $schema = "buildflow_${service}_$suffix"
            Assert-MySqlIdentifier -Value $schema
            [void](Invoke-Sql -Sql "CREATE DATABASE ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
        }
    }

    # 1) Schema A: production way (ddl-auto update; auth = schema.sql + validate).
    $phaseA = Invoke-Phase -PhaseServices $Services -Phase 'legacy' -ArgumentsFor { param($s) @{ Schema = "buildflow_${s}_legacy"; Extra = @() } }
    # 2) Schema B: opt-in Flyway V1 profile (also proves V1 + Hibernate validate on an empty schema).
    $phaseB = Invoke-Phase -PhaseServices $Services -Phase 'v1' -ArgumentsFor { param($s) @{ Schema = "buildflow_${s}_v1"; Extra = @("--spring.profiles.active=$s-flyway") } }

    # 3) Compare.
    $summary = [ordered]@{}
    $eligible = @()
    foreach ($service in $Services) {
        $a = "buildflow_${service}_legacy"; $b = "buildflow_${service}_v1"
        $row = [ordered]@{ legacyStart = $phaseA[$service].Outcome; v1Start = $phaseB[$service].Outcome; tables = 0; charsetNotation = '-'; semanticDiff = @(); orderOnlyTables = @(); ddlTextDiff = @(); baseline = 'SKIPPED'; verify = 'SKIPPED'; history = @() }
        if ($phaseA[$service].Outcome -ne 'STARTED' -or $phaseB[$service].Outcome -ne 'STARTED') {
            $row.baseline = 'BLOCKED (startup)'
            $summary[$service] = $row
            Add-Report "[$service] startup failed: legacy=$($row.legacyStart) v1=$($row.v1Start) $($phaseA[$service].Errors + $phaseB[$service].Errors -join ' / ')"
            continue
        }
        $ddlA = Get-NormalizedCreateTables -Schema $a
        $ddlB = Get-NormalizedCreateTables -Schema $b
        $writer = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText((Join-Path $OutputDirectory "$service-legacy.sql"), (($ddlA.Keys | ForEach-Object { ($ddlA[$_] -join "`n") + ";`n" }) -join "`n"), $writer)
        [IO.File]::WriteAllText((Join-Path $OutputDirectory "$service-v1.sql"), (($ddlB.Keys | ForEach-Object { ($ddlB[$_] -join "`n") + ";`n" }) -join "`n"), $writer)
        $row.tables = @($ddlA.Keys).Count
        $row.charsetNotation = "{0}/{1}" -f [int]$script:explicitCharset[$a], [int]$script:explicitCharset[$b]
        $tableDiff = Compare-Lines -Left @($ddlA.Keys) -Right @($ddlB.Keys)
        foreach ($t in $tableDiff.OnlyLeft) { $row.semanticDiff += "table only in ddl-auto: $t" }
        foreach ($t in $tableDiff.OnlyRight) { $row.semanticDiff += "table only in V1: $t" }
        foreach ($table in @($ddlA.Keys | Where-Object { $ddlB.Contains($_) })) {
            $la = @($ddlA[$table]); $lb = @($ddlB[$table])
            if (($la -join "`n") -ceq ($lb -join "`n")) { continue }
            $d = Compare-Lines -Left $la -Right $lb
            if ($d.OnlyLeft.Count -eq 0 -and $d.OnlyRight.Count -eq 0) { $row.orderOnlyTables += $table; continue }
            foreach ($l in $d.OnlyLeft) { $row.ddlTextDiff += "$table ddl-auto: $l" }
            foreach ($l in $d.OnlyRight) { $row.ddlTextDiff += "$table V1: $l" }
        }
        $fa = Get-SchemaFacts -Schema $a; $fb = Get-SchemaFacts -Schema $b
        $fd = Compare-Lines -Left $fa.Facts -Right $fb.Facts
        foreach ($l in $fd.OnlyLeft) { $row.semanticDiff += "ddl-auto: $l" }
        foreach ($l in $fd.OnlyRight) { $row.semanticDiff += "V1: $l" }
        $summary[$service] = $row
        if ($row.semanticDiff.Count -eq 0 -and $row.ddlTextDiff.Count -eq 0) { $eligible += $service }
    }

    # 4) One-time baseline(version=1) on schema A, then a normal flyway-profile start (migrate 0 + validate).
    if ($eligible.Count -gt 0) {
        $baselineArgs = { param($s) @{ Schema = "buildflow_${s}_legacy"; Extra = @("--spring.profiles.active=$s-flyway", '--spring.flyway.baseline-on-migrate=true', '--spring.flyway.baseline-version=1', '--spring.flyway.baseline-description=rehearsal one-time baseline') } }
        $phaseC = Invoke-Phase -PhaseServices $eligible -Phase 'baseline' -ArgumentsFor $baselineArgs
        foreach ($service in $eligible) {
            $summary[$service].baseline = $phaseC[$service].Outcome
            $summary[$service].baselineHistory = @(Get-HistoryRows -Schema "buildflow_${service}_legacy")
        }
        $verifyServices = @($eligible | Where-Object { $phaseC[$_].Outcome -eq 'STARTED' })
        if ($verifyServices.Count -gt 0) {
            $phaseD = Invoke-Phase -PhaseServices $verifyServices -Phase 'verify' -ArgumentsFor { param($s) @{ Schema = "buildflow_${s}_legacy"; Extra = @("--spring.profiles.active=$s-flyway") } }
            foreach ($service in $verifyServices) {
                $history = @(Get-HistoryRows -Schema "buildflow_${service}_legacy")
                $v1History = @(Get-HistoryRows -Schema "buildflow_${service}_v1")
                $upToDate = @($phaseD[$service].Flyway | Where-Object { $_ -match 'up to date|No migration necessary' }).Count -gt 0
                $ok = $phaseD[$service].Outcome -eq 'STARTED' -and $upToDate -and ($history -join ';') -ceq (Get-ExpectedHistory -Service $service -FirstType 'BASELINE') -and ($v1History -join ';') -ceq (Get-ExpectedHistory -Service $service -FirstType 'SQL')
                $summary[$service].verify = if ($ok) { 'PASS' } else { "FAIL ($($phaseD[$service].Outcome), upToDate=$upToDate, history=$($history -join ';'), v1=$($v1History -join ';'))" }
                $summary[$service].history = $history
            }
        }
    }

    Add-Report ''
    Add-Report 'service      | tables | semantic diff | ddl text diff | charset notation A/B | order-only tables | baseline | migrate0+validate'
    foreach ($service in $Services) {
        $r = $summary[$service]
        Add-Report ('{0,-12} | {1,6} | {2,13} | {3,13} | {4,20} | {5,-17} | {6,-8} | {7}' -f $service, $r.tables, $r.semanticDiff.Count, $r.ddlTextDiff.Count, $r.charsetNotation, ($r.orderOnlyTables -join ','), $r.baseline, $r.verify)
    }
    foreach ($service in $Services) {
        $r = $summary[$service]
        foreach ($l in @($r.semanticDiff) + @($r.ddlTextDiff)) { Add-Report "  [$service] $l" }
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines((Join-Path $OutputDirectory 'report.txt'), $report, $encoding)
    Write-JsonUtf8 -Path (Join-Path $OutputDirectory 'summary.json') -Value $summary
    $failed = @($Services | Where-Object { $summary[$_].verify -ne 'PASS' })
    if ($failed.Count -gt 0) { Write-Host "NOT PASSED: $($failed -join ', ')"; $script:exitCode = 1 } else { Write-Host 'ALL PASSED'; $script:exitCode = 0 }
}
finally {
    foreach ($name in @($appContainers)) {
        try { Stop-ServiceRun -Name $name } catch { Write-Warning "App container cleanup failed: $name ($($_.Exception.Message))" }
    }
    if ($started) {
        $ids = @(Get-OutputLines -Text (Invoke-Docker -Arguments @('ps', '-aq', '--filter', "label=com.docker.compose.project=$project")))
        $safe = $true
        foreach ($id in $ids) {
            $item = @((Invoke-Docker -Arguments @('inspect', $id) | ConvertFrom-Json))[0]
            if ([string]$item.Config.Labels.$labelKey -cne $nonce -or [string]$item.Config.Labels.'com.docker.compose.service' -cne 'mysql') { $safe = $false }
        }
        if ($safe -and $project -cmatch '^bfbase\d+$') {
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
