#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
COMPOSE="docker compose -f docker-compose.yml"

info() { echo "[$(date +%H:%M:%S)] $*"; }
warn() { echo "[$(date +%H:%M:%S)] WARN: $*"; }

# Проверка Docker
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker недоступен. Запусти Docker Desktop на Windows и подожди 30 секунд."
  exit 1
fi

# Проверка только нужных для админки JAR'ов
check_jars() {
  local missing=0
  for svc in crypto-service gateway-service user-service admin-service route-service; do
    jar_path="$SCRIPT_DIR/../../backend/$svc/build/libs/$svc-*.jar"
    if ! ls $jar_path >/dev/null 2>&1; then
      warn "JAR не найден: $svc (нужен ./gradlew bootJar)"
      missing=1
    fi
  done
  if [ $missing -eq 1 ]; then
    echo ""
    echo "Собери JAR'ы и запусти снова:"
    echo "  ./gradlew bootJar --no-daemon"
    echo ""
  fi
}

check_jars

info "=== Wave 1: База данных + координатор ==="
$COMPOSE up -d postgres zookeeper
info "Ожидание 30 сек..."
sleep 30

info "=== Wave 2: Криптосервис + миграции ==="
$COMPOSE up -d --build crypto-service liquibase
info "Ожидание 45 сек для crypto-service..."
sleep 45

info "=== Wave 3: Сертификаты ==="
$COMPOSE up -d certs-init
info "Ожидание 30 сек для certs-init..."
sleep 30

info "=== Wave 4: Брокер сообщений ==="
$COMPOSE up -d kafka
info "Ожидание 60 сек для Kafka..."
sleep 60

info "=== Wave 5: Keycloak ==="
$COMPOSE up -d keycloak
info "Ожидание 60 сек для Keycloak..."
sleep 60

info "=== Wave 6: Application-сервисы (только админка) ==="
$COMPOSE up -d --no-deps --build gateway-service user-service admin-service route-service
info "Ожидание 30 сек..."
sleep 30

info "=== Wave 7: Фронтенд ==="
$COMPOSE up -d --no-deps --build web-admin

info ""
info "=== Статус контейнеров ==="
$COMPOSE ps

info ""
info "=== Логи одноразовых контейнеров ==="
for svc in certs-init liquibase; do
  $COMPOSE ps --all | grep -q "$svc" && $COMPOSE logs --tail=10 "$svc" || true
done

info ""
info "Готово! Проверка healthcheck'ов через 15 сек..."
sleep 15

UNHEALTHY=$($COMPOSE ps 2>/dev/null | grep -c "unhealthy" || true)
if [ "$UNHEALTHY" -gt 0 ]; then
  warn "Найдено $UNHEALTHY unhealthy контейнеров:"
  $COMPOSE ps | grep "unhealthy"
  warn "Проверь логи: docker compose logs <service>"
else
  info "Все контейнеры здоровы!"
fi

info ""
info "Админка доступна: https://localhost:3443"
