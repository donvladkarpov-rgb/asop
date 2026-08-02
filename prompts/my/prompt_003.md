# Доработка

1) выполни промпт prompts/my/prompt_003_01.md
2) Для поля UPDATED_AT в таблицах/справочниках сделать триггер, котороый автоматом заполняет поле датой при апдейтах и
инсертах. Значение прописанное в инсерте или апдейте игнорировать.
3) Сделать ContentProvider внутри android-terminal (с query API) для всех таблиц, которые есть в его базе
4) На терминале при регистрации пользователь вводит регион и перевозчика и тайм зону, необходиом их сохранить в базе и
передавать их во всех запросах к серверу. Ресолв региона и перевозчика из гейтвея убрать. В doc/architecture.md и в
   doc/context.md и в AGENTS.md зафиксировать, что гейтвей только для авторезации, аутентификации и проксирования, 
бизнес логики там нет и быть не может.
5) выполни промпт prompts/my/prompt_003_01.md

# Тестирование

1) С Целью тестирования работы, описанной в prompts/prompt_002.md и в prompts/prompt_002_01.md напиши sql скрипты 
подобные infrastructure/docker/seed-data.sql, но другие, отдельные, назови infrastructure/docker/seed-data-delta-1.sql, 
infrastructure/docker/seed-data-delta-2.sql и infrastructure/docker/seed-data-delta-3.sql,
для заполнения таблиц/справочников: 

### [0. Регионы и Территории (ФИАС/ГАР)](#0-регионы-и-территории-фиасгар)

* [`ASOP_REGIONS`](#asop_regions)  [admin-service]
* [`ASOP_TERRITORIES`](#asop_territories)  [admin-service]
* [`ASOP_ORGANIZERS`](#asop_organizers)  [admin-service]
* [`ASOP_ORGANIZER_TERRITORIES`](#asop_organizer_territories) [admin-service]

### [1. Справочники](#1-справочники)

* [`ASOP_ROLES`](#asop_roles)   [admin-service]
* [`ASOP_CARD_TYPES`](#asop_card_types) [admin-service]
* [`ASOP_TARIFF_TYPES`](#asop_tariff_types) [admin-service]
* [`ASOP_SESSION_TYPES`](#asop_session_types) [admin-service]
* [`ASOP_EVENT_TYPES`](#asop_event_types) [admin-service]
* [`ASOP_TRANSACTION_TYPES`](#asop_transaction_types) [admin-service]
* [`ASOP_TRANSACTION_RESULTS`](#asop_transaction_results) [admin-service]
* [`ASOP_SERVICES`](#asop_services) [admin-service]
* [`ASOP_BENEFITS`](#asop_benefits) [admin-service]
* [`ASOP_BENEFIT_STEPS`](#asop_benefit_steps) [admin-service]

### [2. Перевозчики, Договоры и ТС](#2-перевозчики-договоры-и-тс)

* [`ASOP_CARRIERS`](#asop_carriers) [carrier-service]
* [`ASOP_CONTRACTS`](#asop_contracts) [carrier-service]
* [`ASOP_CONTRACT_ROUTES`](#asop_contract_routes) [carrier-service]
* [`ASOP_VEHICLE_TYPES`](#asop_vehicle_types) [carrier-service]
* [`ASOP_VEHICLE_MODELS`](#asop_vehicle_models) [carrier-service]
* [`ASOP_VEHICLES`](#asop_vehicles) [carrier-service]

### [3. Пользователи и Безопасность](#3-пользователи-и-безопасность)

* [`ASOP_USERS`](#asop_users) [user-service]
* [`ASOP_USER_ROLES`](#asop_user_roles) [user-service]
* [`ASOP_USER_CARRIERS`](#asop_user_carriers) [user-service]
* [`ASOP_USER_REGIONS`](#asop_user_regions) [user-service]

### [4. Маршруты и Пути](#4-маршруты-и-пути)

* [`ASOP_FARE_ZONES`](#asop_fare_zones) [route-service]
* [`ASOP_TRANSPORT_STOPS`](#asop_transport_stops) [route-service]
* [`ASOP_ROUTES`](#asop_routes) [route-service]
* [`ASOP_PATHS`](#asop_paths) [route-service]
* [`ASOP_PATH_TRANSPORT_STOPS`](#asop_path_transport_stops) [route-service]
* [`ASOP_SCHEDULE`](#asop_schedule) [route-service]
* [`ASOP_PATH_SERVICES`](#asop_path_services) [route-service]
* [`ASOP_PATH_DISCOUNTS`](#asop_path_discounts) [route-service]
* [`ASOP_PATH_BENEFITS`](#asop_path_benefits) [route-service]

### [5. Карты, Льготы, Тарифы](#5-карты-льготы-тарифы)

* [`ASOP_CARDS`](#asop_cards) [card-service]
* [`ASOP_CARD_MIFARES`](#asop_card_mifares) [card-service]
* [`ASOP_CARD_BANKS`](#asop_card_banks) [card-service]
* [`ASOP_CARD_TARIFFS`](#asop_card_tariffs) [card-service]
* [`ASOP_BLACKLISTS`](#asop_blacklists) [card-service]
* [`ASOP_USER_BENEFITS`](#asop_user_benefits) [card-service]
* [`ASOP_TARIFF_RATES`](#asop_tariff_rates) [card-service]

* [`ASOP_TIDS`](#asop_tids) [carrier-service]

Для скрипта infrastructure/docker/seed-data-delta-1.sql занеси в таблицы примерно по 1000-2000 записей.
Для скрипта infrastructure/docker/seed-data-delta-2.sql занеси в таблицы примерно по 100000-200000 записей.
Для скрипта infrastructure/docker/seed-data-delta-3.sql занеси в таблицы примерно по 10000-20000 записей.

2) Напиши в папке frontend еще одно андроид приложение для тестовых целей, назови android-test, сделай там меню, с тремя
пунктами: "Тест дельта инкремента 1", "Тест дельта инкремента 2", и "Тест выкачки всего сразу". По нажатии на меню  
"Тест дельта инкремента 1" проверять в базе данных приложения android-terminal, что  данные для этого терминала 
(отфильтрованные по региону и/или перевозчику) из  
infrastructure/docker/seed-data-delta-1.sql получены терминалом.
По нажатии на меню "Тест дельта инкремента 2" проверять в базе данных приложения android-terminal, что данные
для этого терминала (отфильтрованные по региону и/или перевозчику) из  
infrastructure/docker/seed-data-delta-1.sql и infrastructure/docker/seed-data-delta-2.sql получены терминалом.
По нажатии на меню "Тест выкачки всего сразу" проверять в базе данных приложения android-terminal, что данные 
для этого терминала (отфильтрованные по региону и/или перевозчику) из  
infrastructure/docker/seed-data-delta-1.sql и infrastructure/docker/seed-data-delta-2.sql и 
infrastructure/docker/seed-data-delta-3.sql получены терминалом.
Данные проверять через ContentProvider внутри android-terminal (с query API).
