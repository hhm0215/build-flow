[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet(
        "eureka-server",
        "config-server",
        "gateway-server",
        "auth-service",
        "estimate-service",
        "site-service",
        "purchase-service",
        "tax-service",
        "notification-service",
        "chat-service"
    )]
    [string]$Service
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repoRoot ".env"
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $envFile)) {
    throw ".env is missing. Run '.\scripts\buildflow.ps1 check' first."
}

Get-Content -LiteralPath $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        $name = $Matches[1].Trim()
        $value = $Matches[2]
        Set-Item -Path "Env:$name" -Value $value
    }
}

# Host addresses for infrastructure running in Docker.
$env:DB_HOST = "localhost"
$env:REDIS_HOST = "localhost"
$env:KAFKA_BOOTSTRAP_SERVERS = "localhost:9094"
$env:MANAGEMENT_ZIPKIN_TRACING_ENDPOINT = "http://localhost:9411/api/v2/spans"

if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("OLLAMA_URL"))) {
    $env:OLLAMA_URL = "http://localhost:11434"
}

if ($Service -eq "config-server") {
    $env:SPRING_PROFILES_ACTIVE = "native"
}
else {
    Remove-Item Env:SPRING_PROFILES_ACTIVE -ErrorAction SilentlyContinue
}

if ($Service -eq "notification-service") {
    Write-Warning "OCR fallback requires native Tesseract on Windows. Prefer Docker for notification-service."
}

Push-Location $repoRoot
$gradleExitCode = 1
try {
    & $gradleWrapper ":${Service}:bootRun" "--no-daemon"
    $gradleExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

exit $gradleExitCode
