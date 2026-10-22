# Pure DDL normalization/compare helpers shared by flyway-baseline-rehearsal.ps1 and its tests.
# No Docker, no DB access. Windows PowerShell 5.1 compatible.

$script:CharsetNotation = ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci'

function ConvertFrom-MySqlBatchEscape {
    # mysql --batch (without --raw) prints \n, \t, \\ and \0 escapes; turn them back into text.
    param([AllowEmptyString()][string]$Text)
    return [regex]::Replace($Text, '\\(.)', {
            param($m)
            switch -CaseSensitive ($m.Groups[1].Value) {
                'n' { "`n" }
                't' { "`t" }
                '0' { [string][char]0 }
                default { $m.Groups[1].Value }
            }
        })
}

function Get-CharsetNotationCount {
    param([AllowEmptyString()][string]$Ddl)
    return ([regex]::Matches($Ddl, [regex]::Escape($script:CharsetNotation))).Count
}

function ConvertTo-NormalizedDdlLines {
    # One SHOW CREATE TABLE statement (real newlines) -> comparable lines.
    # AUTO_INCREMENT=N is data, not schema. Columns created from SQL with an explicit COLLATE are
    # printed with a redundant "CHARACTER SET utf8mb4" (data-dictionary notation only).
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Ddl)
    $text = $Ddl -replace ' AUTO_INCREMENT=\d+', ''
    $text = $text.Replace($script:CharsetNotation, ' COLLATE utf8mb4_unicode_ci')
    $lines = @($text -split "`r?`n" | ForEach-Object { $_.Trim().TrimEnd(';').TrimEnd(',') } | Where-Object { $_ -ne '' })
    return , $lines
}

function ConvertFrom-DdlDump {
    # Parses `buildflow-release schema` output (or a rehearsal <service>-v1.sql file) into
    # @{ schema = [ordered]@{ table = string[] } }. Statements without a "-- schema:" marker go to ''.
    # flyway_schema_history is ignored: it is Flyway bookkeeping, not V1 content.
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    $result = @{}
    $schema = ''
    $buffer = $null
    $table = $null
    foreach ($raw in ($Text -split "`r?`n")) {
        $line = $raw.TrimEnd()
        if ($null -eq $buffer) {
            if ($line -match '^-- schema: ([A-Za-z0-9_]+)\s*$') {
                $schema = $Matches[1]
                if (-not $result.ContainsKey($schema)) { $result[$schema] = [ordered]@{} }  # an empty schema is still present
                continue
            }
            if ($line -match '^\s*CREATE TABLE `([A-Za-z0-9_]+)`') {
                $table = $Matches[1]
                $buffer = New-Object System.Collections.Generic.List[string]
            }
            else { continue }
        }
        $buffer.Add($line)
        if ($line.EndsWith(';')) {
            if (-not $result.ContainsKey($schema)) { $result[$schema] = [ordered]@{} }
            if ($result[$schema].Contains($table)) { throw "Duplicate table in DDL dump: $schema.$table" }
            if ($table -cne 'flyway_schema_history') {
                $result[$schema][$table] = ConvertTo-NormalizedDdlLines -Ddl ($buffer -join "`n")
            }
            $buffer = $null
            $table = $null
        }
    }
    if ($null -ne $buffer) { throw "Unterminated CREATE TABLE in DDL dump: $schema.$table" }
    return $result
}

function Compare-Lines {
    param([string[]]$Left = @(), [string[]]$Right = @())
    $onlyLeft = @($Left | Where-Object { $_ -cnotin $Right })
    $onlyRight = @($Right | Where-Object { $_ -cnotin $Left })
    return [pscustomobject]@{ OnlyLeft = $onlyLeft; OnlyRight = $onlyRight }
}

function Compare-DdlMaps {
    # Left/Right: [ordered] table -> normalized lines. Returns counts plus readable differences.
    param(
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Left,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Right,
        [string]$LeftName = 'left',
        [string]$RightName = 'right'
    )
    $tables = Compare-Lines -Left @($Left.Keys) -Right @($Right.Keys)
    $diff = New-Object System.Collections.Generic.List[string]
    $orderOnly = New-Object System.Collections.Generic.List[string]
    $lineDiffTables = New-Object System.Collections.Generic.List[string]
    foreach ($t in $tables.OnlyLeft) { $diff.Add("table only in ${LeftName}: $t") }
    foreach ($t in $tables.OnlyRight) { $diff.Add("table only in ${RightName}: $t") }
    foreach ($table in @($Left.Keys | Where-Object { $Right.Contains($_) })) {
        $la = @($Left[$table]); $lb = @($Right[$table])
        if (($la -join "`n") -ceq ($lb -join "`n")) { continue }
        $d = Compare-Lines -Left $la -Right $lb
        if ($d.OnlyLeft.Count -eq 0 -and $d.OnlyRight.Count -eq 0) { $orderOnly.Add($table); continue }
        $lineDiffTables.Add($table)
        foreach ($l in $d.OnlyLeft) { $diff.Add("$table ${LeftName}: $l") }
        foreach ($l in $d.OnlyRight) { $diff.Add("$table ${RightName}: $l") }
    }
    return [pscustomobject]@{
        LeftTables      = @($Left.Keys).Count
        RightTables     = @($Right.Keys).Count
        OnlyLeftTables  = @($tables.OnlyLeft)
        OnlyRightTables = @($tables.OnlyRight)
        LineDiffTables  = @($lineDiffTables)
        OrderOnlyTables = @($orderOnly)
        Differences     = @($diff)
    }
}

function Compare-ProdDdl {
    # Prod dump (all buildflow_<service> schemas) vs per-service V1 maps (service -> table map).
    param(
        [Parameter(Mandatory = $true)][hashtable]$Prod,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$V1ByService
    )
    $rows = [ordered]@{}
    foreach ($service in @($V1ByService.Keys)) {
        $schema = "buildflow_$service"
        $prodMap = if ($Prod.ContainsKey($schema)) { $Prod[$schema] } else { [ordered]@{} }
        $rows[$service] = Compare-DdlMaps -Left $prodMap -Right $V1ByService[$service] -LeftName 'prod' -RightName 'V1'
        $rows[$service] | Add-Member -NotePropertyName SchemaPresent -NotePropertyValue $Prod.ContainsKey($schema)
    }
    return $rows
}

function Format-ProdDdlReport {
    param([Parameter(Mandatory = $true)][System.Collections.IDictionary]$Rows)
    $out = New-Object System.Collections.Generic.List[string]
    $out.Add('service      | prod tables | V1 tables | only prod | only V1 | line-diff tables | order-only | result')
    foreach ($service in @($Rows.Keys)) {
        $r = $Rows[$service]
        $result = if (-not $r.SchemaPresent) { 'NO SCHEMA IN DUMP' } elseif ($r.Differences.Count -eq 0) { 'MATCH' } else { 'DIFF' }
        $out.Add(('{0,-12} | {1,11} | {2,9} | {3,9} | {4,7} | {5,16} | {6,-10} | {7}' -f $service, $r.LeftTables, $r.RightTables, $r.OnlyLeftTables.Count, $r.OnlyRightTables.Count, $r.LineDiffTables.Count, ($r.OrderOnlyTables -join ','), $result))
    }
    foreach ($service in @($Rows.Keys)) {
        foreach ($l in $Rows[$service].Differences) { $out.Add("  [$service] $l") }
    }
    return $out.ToArray()
}
