#!/usr/bin/env pwsh
# start-admin.ps1 — wave-based запуск только админки ASOP Platform в Docker
# Usage: .\start-admin.ps1
#        .\start-admin.ps1 -SkipBuild
#        .\start-admin.ps1 -DryRun

[CmdletBinding()]
param(
  [switch]$SkipBuild,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

function Info([string]$Msg) { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Msg" -ForegroundColor Cyan }
function Warn([string]$Msg) { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] WARN: $Msg" -ForegroundColor Yellow }
function Ok([string]$Msg)   { Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Msg" -ForegroundColor Green }

# Проверка Docker
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Write-Host "ERROR: Docker недоступен. Запусти Docker Desktop и подожди 30 секунд." -ForegroundColor Red
  exit 1
}
try { docker info *>$null } catch {
  Write-Host "ERROR: Docker daemon не отвечает. Проверь Docker Desktop." -ForegroundColor Red
  exit 1
}

# Проверка JARs (только нужные для админки)
$Services = @('crypto-service','gateway-service','user-service','admin-service')
$missing = $false
foreach ($svc in $Services) {
  $jarPattern = Join-Path $ScriptDir "..\..\backend\$svc\build\libs\$svc-*.jar"
  if (-not (Get-ChildItem $jarPattern -ErrorAction SilentlyContinue)) {
    Warn "JAR не найден: $svc (нужен ./gradlew bootJar)"
    $missing = $true
  }
}
if ($missing) {
  Write-Host ""
  Write-Host "Собери JAR'ы и запусти снова:" -ForegroundColor Yellow
  Write-Host "  .\gradlew.bat bootJar --no-daemon" -ForegroundColor Yellow
  Write-Host ""
  exit 1
}

function Invoke-Wave {
  param(
    [string]$Name,
    [string[]]$Services,
    [int]$WaitSec,
    [switch]$Build,
    [switch]$NoDeps
  )
  Info "=== $Name ==="
  $args = @("compose", "-f", "docker-compose.yml", "up", "-d")
  if ($NoDeps) { $args += "--no-deps" }
  if ($Build -and -not $SkipBuild) { $args += "--build" }
  $args += $Services
  if ($DryRun) {
    Write-Host "  [DRY-RUN] docker $args" -ForegroundColor DarkGray
  } else {
    & docker @args
    if ($LASTEXITCODE -ne 0) { Warn "Wave завершилась с кодом $LASTEXITCODE" }
  }
  if ($WaitSec -gt 0) {
    Info "Ожидание $WaitSec сек..."
    if (-not $DryRun) { Start-Sleep -Seconds $WaitSec }
  }
}

# Wave 1
Invoke-Wave "Wave 1: База данных + координатор" `
  -Services @("postgres","zookeeper") -WaitSec 30

# Wave 2
Invoke-Wave "Wave 2: Криптосервис + миграции" `
  -Services @("crypto-service","liquibase") -WaitSec 45 -Build

# Wave 3
Invoke-Wave "Wave 3: Сертификаты" `
  -Services @("certs-init") -WaitSec 30

# Wave 4
Invoke-Wave "Wave 4: Брокер сообщений (Kafka)" `
  -Services @("kafka") -WaitSec 60

# Wave 5
Invoke-Wave "Wave 5: Keycloak" `
  -Services @("keycloak") -WaitSec 60

# Wave 6 — только админка
Invoke-Wave "Wave 6: Application-сервисы (только админка)" `
  -Services @("gateway-service","user-service","admin-service") -WaitSec 30 -Build -NoDeps

# Wave 7 — фронт
Invoke-Wave "Wave 7: Фронтенд" `
  -Services @("web-admin") -WaitSec 0 -Build -NoDeps

# Статус
Info ""
Info "=== Статус контейнеров ==="
if (-not $DryRun) {
  docker compose -f docker-compose.yml ps
}

# Логи одноразовых
Info ""
Info "=== Логи одноразовых контейнеров ==="
if (-not $DryRun) {
  foreach ($svc in @("certs-init","liquibase")) {
    $status = docker compose -f docker-compose.yml ps --all --format json $svc 2>$null
    if ($status) {
      Info "Логи $svc (последние 10 строк):"
      docker compose -f docker-compose.yml logs --tail=10 $svc
    }
  }
}

# Healthcheck
Info ""
Info "Проверка healthcheck'ов через 15 сек..."
if (-not $DryRun) { Start-Sleep -Seconds 15 }

$unhealthy = docker compose -f docker-compose.yml ps 2>$null | Select-String "unhealthy"
if ($unhealthy) {
  Warn "Найдено unhealthy контейнеров: $($unhealthy.Count)"
  $unhealthy | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
  Warn "Проверь логи: docker compose -f docker-compose.yml logs <service>"
} else {
  Ok "Все контейнеры здоровы!"
}
