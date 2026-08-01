-- Поднимает user 'asop' до SUPERUSER. Исполняется docker-entrypoint
-- при первом запуске (пустой volume) ПОСЛЕ создания дефолтной database/user.
-- Нужен PurgeJob (SET session_replication_role = 'replica') в orchestrator-service.
ALTER USER asop WITH SUPERUSER;
