#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"
$COMPOSE = "docker compose -f docker-compose.yml"

function info  { Write-Host "[$(Get-Date -Format HH:mm:ss)] $args" -ForegroundColor Green }
function warn  { Write-Host "[$(Get-Date -Format HH:mm:ss)] WARN: $args" -ForegroundColor Yellow }

info "=== Wave 1: База данных + координатор ==="
Invoke-Expression "$COMPOSE up -d postgres zookeeper"
Start-Sleep -Seconds 30

info "=== Wave 2: Криптосервис + миграции ==="
Invoke-Expression "$COMPOSE up -d --build crypto-service liquibase"
Start-Sleep -Seconds 45

info "=== Wave 3: Сертификаты ==="
Invoke-Expression "$COMPOSE up -d certs-init"
Start-Sleep -Seconds 30

info "=== Wave 4: Брокер сообщений ==="
Invoke-Expression "$COMPOSE up -d kafka"
Start-Sleep -Seconds 60

info "=== Wave 5: Gateway + terminal-service ==="
Invoke-Expression "$COMPOSE up -d --no-deps --build gateway-service terminal-service"
Start-Sleep -Seconds 30

info ""
info "=== Статус контейнеров ==="
Invoke-Expression "$COMPOSE ps"

info "Готово!"
Write-Host "Android терминал: https://localhost:8080"
Write-Host "cert-sign: POST /api/v1/terminals/cert-sign (open HTTPS)"
Write-Host "sync endpoints: /api/v1/sync/** (mTLS)"
