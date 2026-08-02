#!/usr/bin/env bash
# ============================================================
# Генерация ethalon JSON для android-test из Postgres.
# Зеркалит логику мастер-сервисов /delta (фильтры) + JsonFormat.
# Вывод: { "asop_benefits": [...], "asop_roles": [...], ... }
#
# Срезы (полный снапшот БД в трёх состояниях):
#   expected-1.json   — после seed-data.sql + seed-data-delta-1.sql
#   expected-2.json   — после + seed-data-delta-2.sql
#   expected-all.json — после + seed-data-delta-3.sql
#
# Запуск (psql нужен — внутри контейнера postgres):
#   docker exec -i -e PGPASSWORD=asop docker-postgres-1 \
#     bash < infrastructure/docker/generate-ethalon.sh \
#     > frontend/android-test/app/src/main/assets/expected-1.json
#
# Зависит от окружения: PGUSER/PGHOST/PGDATABASE/PGPASSWORD (см. ниже).
# ============================================================

set -euo pipefail

: "${PGHOST:=localhost}"
: "${PGUSER:=asop}"
: "${PGDATABASE:=asop}"
export PGHOST PGUSER PGDATABASE PGPASSWORD

REGION='00000000-0000-0000-0000-000000000103'
CARRIER='00000000-0000-0000-0000-000000001403'

# Возвращает SQL-подзапрос множества user_id терминала
users_set() {
    echo "SELECT user_id FROM asop_user_carriers WHERE carrier_id = '$CARRIER' UNION SELECT user_id FROM asop_user_regions WHERE region_id = '$REGION'"
}

# Печатает один блок: "  \"table\": [json]"
# $1 table  $2 WHERE  $3 columns
gen() {
    local table="$1" where="$2" columns="$3"
    local q="SELECT COALESCE(json_agg(t), '[]') FROM (SELECT $columns FROM $table ${where:-} ORDER BY version ASC) t;"
    echo "  \"$table\": $(psql -t -A -c "$q")"
}

main() {
    local users
    users=$(users_set)
    local cards
    cards="SELECT card_id FROM asop_cards WHERE user_id IN ($users)"

    echo "{"
    local first=1
    # обёртка: разделитель "," между блоками
    row() {
        if [ "$first" = "0" ]; then echo ","; fi
        first=0
        gen "$@"
    }

    # ---- GLOBAL ----
    row asop_regions "" "version, deleted_at, region_id, municipal_division, admin_division, federal_district, ifns_fl_code, ifns_ul_code, okato_code, oktmo_code, oktmo_budget_code, fias_id, registry_record_id, timezone"
    row asop_territories "" "version, deleted_at, territory_id, region_id, municipal_division, admin_division, federal_district, ifns_fl_code, ifns_ul_code, okato_code, oktmo_code, oktmo_budget_code, fias_id, registry_record_id"
    row asop_organizers "" "version, deleted_at, organizer_id, organizer_name"
    row asop_organizer_territories "" "version, deleted_at, organizer_id, territory_id"
    row asop_roles "" "version, deleted_at, role_id, role_name"
    row asop_card_types "" "version, deleted_at, card_type_id, card_type_name"
    row asop_tariff_types "" "version, deleted_at, tariff_type_id, code, name, description"
    row asop_session_types "" "version, deleted_at, session_type_id, session_type_code, session_type_name"
    row asop_event_types "" "version, deleted_at, event_type, event_type_name"
    row asop_transaction_types "" "version, deleted_at, transaction_type_id, transaction_type_name"
    row asop_transaction_results "" "version, deleted_at, transaction_result_id, transaction_result_name"
    row asop_benefit_steps "" "version, deleted_at, step_id, benefit_id, step_order, trip_threshold_from, trip_threshold_to, discount_share, period_type"
    row asop_cards_distributors "" "version, deleted_at, cards_distributor_id, distributor_name, inn, kpp, legal_address, contact_phone, contact_email, is_active"
    row asop_vehicle_types "" "version, deleted_at, vehicle_type_id, type_name"
    row asop_vehicle_models "" "version, deleted_at, vehicle_model_id, model_name"
    row asop_contract_routes "" "version, deleted_at, contract_id, route_id"
    row asop_path_benefits "" "version, deleted_at, path_benefit_id, path_id, benefit_id"
    row asop_user_roles "" "version, deleted_at, user_id, role_id"
    row asop_tariff_rates "" "version, deleted_at, tariff_rate_id, tariff_type_id, carrier_id, zone_id, path_id, price, description, is_active"

    # ---- FILTERED regionId ----
    row asop_services "WHERE region_id = '$REGION'" "version, deleted_at, service_id, service_name, description, priority, region_id"
    row asop_benefits "WHERE region_id = '$REGION'" "version, deleted_at, benefit_id, benefit_code, benefit_name, region_id, description, is_active"
    row asop_carriers "WHERE region_id = '$REGION'" "version, deleted_at, carrier_id, carrier_name, inn, region_id"
    row asop_fare_zones "WHERE region_id = '$REGION'" "version, deleted_at, zone_id, zone_code, zone_name, description, region_id"
    row asop_transport_stops "WHERE region_id = '$REGION'" "version, deleted_at, stop_id, fare_zone_id, region_id, stop_code, stop_name, stop_address, description, is_active"
    row asop_routes "WHERE region_id = '$REGION'" "version, deleted_at, route_id, route_number, route_name, organizer_id, ministry_registry_no, route_category, region_id"
    row asop_paths "WHERE region_id = '$REGION'" "version, deleted_at, path_id, route_id, path_name, start_stop_id, end_stop_id, benefit_policy, path_start_date, path_end_date, description, region_id"
    row asop_path_transport_stops "WHERE region_id = '$REGION'" "version, deleted_at, path_stop_id, path_id, stop_id, serial_number, region_id"
    row asop_schedule "WHERE region_id = '$REGION'" "version, deleted_at, schedule_id, path_id, stop_id, day_mask, arrival_time, dwell_time_sec, region_id, is_active"

    # ---- FILTERED carrierId ----
    row asop_contracts "WHERE carrier_id = '$CARRIER'" "version, deleted_at, contract_id, contractor_type, carrier_id, cards_distributor_id, contract_number, start_date, end_date, status, commission_percent::text AS commission_percent, attributes::text AS attributes"
    row asop_tids "WHERE carrier_id = '$CARRIER'" "version, deleted_at, tid_id, carrier_id, terminal_id, tid_value, status, assigned_at, unassigned_at"
    row asop_vehicles "WHERE carrier_id = '$CARRIER'" "version, deleted_at, vehicle_id, carrier_id, vehicle_type_id, vehicle_model_id, vehicle_number, vehicle_name"
    row asop_path_services "WHERE carrier_id = '$CARRIER'" "version, deleted_at, path_service_id, path_id, service_id, carrier_id, vehicle_id, tariff_type_id, price, is_active"
    row asop_path_discounts "WHERE carrier_id = '$CARRIER'" "version, deleted_at, path_discount_id, path_id, carrier_id, vehicle_id, tariff_type_id, discount_name, discount_type, discount_value, valid_from, valid_until, is_active"

    # ---- USER ----
    row asop_users "WHERE user_id IN ($users)" "version, deleted_at, user_id, first_name, last_name_initial, patronymic_initial, phone, keycloak_id"
    row asop_user_carriers "WHERE carrier_id = '$CARRIER'" "version, deleted_at, user_id, carrier_id"
    row asop_user_regions "WHERE region_id = '$REGION'" "version, deleted_at, user_id, region_id"

    # ---- CARD ----
    row asop_cards "WHERE user_id IN ($users)" "version, deleted_at, card_id, card_type_id, user_id, is_primary, registered_at"
    row asop_card_mifares "WHERE card_id IN ($cards)" "version, deleted_at, card_id, encode(uid, 'base64') AS uid, atqa, sak, protocol_version, card_role, key_version"
    row asop_card_banks "WHERE card_id IN ($cards)" "version, deleted_at, card_id, pan_token, pan_last4, bin, is_tokenized"
    row asop_card_tariffs "WHERE card_id IN ($cards)" "version, deleted_at, card_tariff_id, card_id, tariff_type_id, balance, travel_count, max_travel_count, expiration_date, is_active"
    row asop_blacklists "WHERE card_id IN ($cards)" "version, deleted_at, card_id, block_type, blocked_at, related_debt_id, auto_unblock_on_recovery"
    row asop_user_benefits "WHERE user_id IN ($users)" "version, deleted_at, assignment_id, user_id, benefit_id, valid_from, valid_until, sync_version"

    echo ""
    echo "}"
}

main
