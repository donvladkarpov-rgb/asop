#!/bin/bash
# start.sh — wave-based запуск ASOP Platform в Docker (аналог start.ps1)
# Usage: ./start.sh
#        ./start.sh --skip-build   (без --build, если образы уже свежие)
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
COMPOSE="docker compose -f docker-compose.yml"

SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    *) echo "Unknown arg: $arg (supported: --skip-build)"; exit 1 ;;
  esac
done

info() { echo "[$(date +%H:%M:%S)] $*"; }
warn() { echo "[$(date +%H:%M:%S)] WARN: $*"; }

# Проверка Docker
if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker недоступен. Запусти Docker Desktop и подожди 30 секунд."
  echo "       После этого выполни скрипт снова."
  exit 1
fi

# Проверка что собирали JAR'ы
check_jars() {
  local missing=0
  for svc in gateway-service crypto-service user-service terminal-service session-service card-service carrier-service debt-service audit-service fiscal-service admin-service route-service orchestrator-service; do
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
    exit 1
  fi
}

check_jars

# Helper для запуска wave
invoke_wave() {
  local name="$1" wait_sec="$2" build="$3" no_deps="$4"; shift 4
  info "=== $name ==="
  local args="up -d"
  [ "$no_deps" = "1" ] && args="$args --no-deps"
  [ "$build" = "1" ] && [ $SKIP_BUILD -eq 0 ] && args="$args --build"
  # shellcheck disable=SC2086
  $COMPOSE $args "$@"
  if [ "$wait_sec" -gt 0 ]; then
    info "Ожидание $wait_sec сек..."
    sleep "$wait_sec"
  fi
}

# Wave 1: БД + координатор
invoke_wave "Wave 1: База данных + координатор" 30 0 0 postgres zookeeper

# Wave 2: Криптосервис + миграции (--build: JAR мог пересобраться)
invoke_wave "Wave 2: Криптосервис + миграции" 45 1 0 crypto-service liquibase

# Wave 3: Сертификаты
invoke_wave "Wave 3: Сертификаты" 30 0 0 certs-init

# Wave 4: Брокер сообщений
invoke_wave "Wave 4: Брокер сообщений (Kafka)" 60 0 0 kafka

# Wave 5: Хранилища (Redis, MinIO S3)
invoke_wave "Wave 5: Хранилища (Redis, MinIO S3)" 30 0 0 redis minio minio-init

# Wave 6: Keycloak
invoke_wave "Wave 6: Keycloak" 60 0 0 keycloak

# Wave 7: Приложения (группа A) — --build обязателен после пересборки JARs
invoke_wave "Wave 7: Приложения (группа A)" 30 1 1 gateway-service user-service admin-service

# Wave 8: Приложения (группа B)
invoke_wave "Wave 8: Приложения (группа B)" 30 1 1 carrier-service terminal-service card-service route-service

# Wave 9: Приложения (группа C) + оркестратор
invoke_wave "Wave 9: Приложения (группа C) + оркестратор" 30 1 1 session-service debt-service audit-service fiscal-service orchestrator-service

# Wave 10: Фронтенд
invoke_wave "Wave 10: Фронтенд" 0 1 1 web-admin

info ""
info "=== Статус всех контейнеров ==="
$COMPOSE ps

info ""
info "=== Логи одноразовых контейнеров (ожидаемо) ==="
for svc in certs-init liquibase; do
  $COMPOSE ps --all 2>/dev/null | grep -q "$svc" && $COMPOSE logs --tail=10 "$svc" || true
done

info ""
info "Проверка healthcheck'ов через 15 сек..."
sleep 15

UNHEALTHY=$($COMPOSE ps 2>/dev/null | grep -c "unhealthy" || true)
if [ "$UNHEALTHY" -gt 0 ]; then
  warn "Найдено $UNHEALTHY unhealthy контейнеров:"
  $COMPOSE ps | grep "unhealthy"
  warn "Проверь логи: docker compose -f docker-compose.yml logs <service>"
else
  info "Все контейнеры здоровы!"
fi
