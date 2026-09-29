Set-StrictMode -Version Latest

function ConvertTo-NativeArgument {
    param([AllowEmptyString()][Parameter(Mandatory = $true)][string]$Argument)

    if ($Argument.Length -eq 0) { return '""' }
    if ($Argument -notmatch '[\s"]') { return $Argument }

    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append('"')
    $backslashCount = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq [char]'\') {
            $backslashCount++
            continue
        }
        if ($character -eq [char]'"') {
            [void]$builder.Append([char]'\', (2 * $backslashCount) + 1)
            [void]$builder.Append('"')
            $backslashCount = 0
            continue
        }
        if ($backslashCount -gt 0) {
            [void]$builder.Append([char]'\', $backslashCount)
            $backslashCount = 0
        }
        [void]$builder.Append($character)
    }
    if ($backslashCount -gt 0) {
        [void]$builder.Append([char]'\', 2 * $backslashCount)
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function New-NativeProcessStartInfo {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [AllowEmptyString()][Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $FileName
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = ($Arguments | ForEach-Object { ConvertTo-NativeArgument -Argument $_ }) -join ' '
    return $startInfo
}

function Invoke-NativeProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [AllowEmptyString()][Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [switch]$RejectStandardError
    )

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = New-NativeProcessStartInfo -FileName $FileName -Arguments $Arguments -WorkingDirectory $WorkingDirectory
    try {
        if (-not $process.Start()) { throw "Could not start process: $FileName" }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "$FileName failed with exit code $($process.ExitCode): $($stderr.Trim())"
        }
        if ($RejectStandardError -and -not [string]::IsNullOrWhiteSpace($stderr)) {
            throw "$FileName wrote unexpected stderr: $($stderr.Trim())"
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StandardOutput = $stdout
            StandardError = $stderr
        }
    }
    finally {
        $process.Dispose()
    }
}

function Get-OutputLines {
    param([AllowEmptyString()][Parameter(Mandatory = $true)][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    return @($Text -split "`r?`n" | Where-Object { $_.Length -gt 0 })
}

function Assert-MySqlIdentifier {
    param([Parameter(Mandatory = $true)][string]$Value)

    if ($Value -cnotmatch '^[a-z0-9_]+$') {
        throw "Unexpected MySQL identifier: $Value"
    }
}

function Write-JsonUtf8 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][object]$Value
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 10), $encoding)
}
