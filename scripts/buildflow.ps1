[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("check", "up", "down", "status", "logs")]
    [string]$Action = "check"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFiles = @(
    "-f", (Join-Path $repoRoot "docker-compose.yml"),
    "-f", (Join-Path $repoRoot "docker-compose.app.yml")
)
$envFile = Join-Path $repoRoot ".env"
$expectedBunVersion = "1.3.11"

function Test-CommandAvailable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [string]$InstallHint,
        [bool]$Required = $true
    )

    if (Get-Command $Name -ErrorAction SilentlyContinue) {
        Write-Host "[OK] $Name"
        return $true
    }

    if ($Required) {
        throw "Command not found: $Name. $InstallHint"
    }

    Write-Warning "Command not found: $Name. $InstallHint"
    return $false
}

function Invoke-BuildFlowCompose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$CommandArgs
    )

    & docker compose @composeFiles @CommandArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE."
    }
}

function New-RandomHex {
    param(
        [Parameter(Mandatory = $true)]
        [int]$ByteCount
    )

    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }

    return -join ($bytes | ForEach-Object { $_.ToString("x2") })
}

function Initialize-EnvFile {
    if (Test-Path $envFile) {
        Write-Host "[OK] Existing .env preserved"
        return
    }

    $databasePassword = New-RandomHex -ByteCount 24
    $jwtSecret = New-RandomHex -ByteCount 64
    $lines = @(
        "# Generated locally by scripts/buildflow.ps1. Do not commit or share this file.",
        "DB_ROOT_PASSWORD=$databasePassword",
        "DB_USERNAME=root",
        "DB_PASSWORD=$databasePassword",
        "JWT_SECRET=$jwtSecret",
        "CLAUDE_API_KEY="
    )
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($envFile, $lines, $utf8WithoutBom)

    $databasePassword = $null
    $jwtSecret = $null
    $lines = $null
    Write-Host "[CREATED] Local .env with random secrets (values are not displayed)"
}

function Test-Docker {
    Test-CommandAvailable -Name "docker" -InstallHint "Install and start Docker Desktop." | Out-Null

    & docker compose version
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose v2 is unavailable. Check Docker Desktop."
    }

    & docker info --format "Docker Engine {{.ServerVersion}}" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot reach the Docker daemon. Start Docker Desktop."
    }
}

function Show-OptionalTools {
    if (Test-CommandAvailable -Name "java" -InstallHint "Host backend development requires JDK 17." -Required $false) {
        & java -version
    }

    if (Test-CommandAvailable -Name "bun" -InstallHint "Host frontend development requires Bun $expectedBunVersion." -Required $false) {
        $bunVersion = (& bun --version).Trim()
        if ($bunVersion -eq $expectedBunVersion) {
            Write-Host "[OK] Bun $bunVersion"
        }
        else {
            Write-Warning "Detected Bun $bunVersion; this project pins $expectedBunVersion."
        }
    }

    if (Test-CommandAvailable -Name "gh" -InstallHint "PR work requires GitHub CLI and 'gh auth login'." -Required $false) {
        & gh --version | Select-Object -First 1
    }
}

function Test-Ollama {
    if (-not (Test-CommandAvailable -Name "ollama" -InstallHint "AI features require Ollama for Windows and qwen2.5:7b." -Required $false)) {
        return
    }

    $models = (& ollama list 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Cannot reach Ollama. Start the Ollama app."
        return
    }

    if ($models -match "qwen2\.5:7b") {
        Write-Host "[OK] Ollama qwen2.5:7b"
    }
    else {
        Write-Warning "Model qwen2.5:7b is missing. Run 'ollama pull qwen2.5:7b'."
    }
}

Push-Location $repoRoot
try {
    Test-CommandAvailable -Name "git" -InstallHint "Install Git for Windows." | Out-Null
    Test-Docker

    if ($Action -in @("check", "up")) {
        Initialize-EnvFile
        Invoke-BuildFlowCompose -CommandArgs @("config", "--quiet")
        Write-Host "[OK] Docker Compose configuration"
        Test-Ollama
    }

    switch ($Action) {
        "check" {
            Show-OptionalTools
            Write-Host "`nReadiness check complete. Start the stack: .\scripts\buildflow.ps1 up"
        }
        "up" {
            Invoke-BuildFlowCompose -CommandArgs @("up", "-d", "--build")
            Invoke-BuildFlowCompose -CommandArgs @("ps")
            Write-Host "`nBuildFlow: http://localhost:3000"
        }
        "down" {
            Invoke-BuildFlowCompose -CommandArgs @("--profile", "container-ollama", "down")
            Write-Host "Named volumes were preserved."
        }
        "status" {
            Invoke-BuildFlowCompose -CommandArgs @("--profile", "container-ollama", "ps")
        }
        "logs" {
            Invoke-BuildFlowCompose -CommandArgs @("--profile", "container-ollama", "logs", "--tail", "200")
        }
    }
}
finally {
    Pop-Location
}
