#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Переносит оставшиеся DTO из crypto-service в crypto-api
"""

from pathlib import Path

ROOT = Path(__file__).parent.resolve()
BACKEND = ROOT / 'backend'
CRYPTO_SERVICE = BACKEND / 'crypto-service'
CRYPTO_API = BACKEND / 'shared' / 'api' / 'crypto-api'

# Какие DTO переносим
DTO_MIGRATIONS = [
    {
        'src': CRYPTO_SERVICE / 'src/main/kotlin/ru/asop/crypto/dto/TerminalCertRequest.kt',
        'dst': CRYPTO_API / 'src/main/kotlin/ru/asop/api/crypto/dto/request/TerminalCertRequest.kt',
        'old_package': 'package ru.asop.crypto.dto',
        'new_package': 'package ru.asop.api.crypto.dto.request',
    },
    {
        'src': CRYPTO_SERVICE / 'src/main/kotlin/ru/asop/crypto/dto/TerminalCertResponse.kt',
        'dst': CRYPTO_API / 'src/main/kotlin/ru/asop/api/crypto/dto/response/TerminalCertResponse.kt',
        'old_package': 'package ru.asop.crypto.dto',
        'new_package': 'package ru.asop.api.crypto.dto.response',
    },
    {
        'src': CRYPTO_SERVICE / 'src/main/kotlin/ru/asop/crypto/dto/SmartCardCertRequest.kt',
        'dst': CRYPTO_API / 'src/main/kotlin/ru/asop/api/crypto/dto/request/SmartCardCertRequest.kt',
        'old_package': 'package ru.asop.crypto.dto',
        'new_package': 'package ru.asop.api.crypto.dto.request',
    },
]

# Какие импорты меняем в контроллерах
IMPORT_REPLACEMENTS = [
    {
        'file': CRYPTO_SERVICE / 'src/main/kotlin/ru/asop/crypto/controller/TerminalCertController.kt',
        'replacements': [
            ('import ru.asop.crypto.dto.TerminalCertRequest', 'import ru.asop.api.crypto.dto.request.TerminalCertRequest'),
            ('import ru.asop.crypto.dto.TerminalCertResponse', 'import ru.asop.api.crypto.dto.response.TerminalCertResponse'),
        ]
    },
    {
        'file': CRYPTO_SERVICE / 'src/main/kotlin/ru/asop/crypto/controller/SmartCardController.kt',
        'replacements': [
            ('import ru.asop.crypto.dto.SmartCardCertRequest', 'import ru.asop.api.crypto.dto.request.SmartCardCertRequest'),
        ]
    },
]


def migrate_dto(migration: dict):
    """Переносит DTO файл с заменой package"""
    src: Path = migration['src']
    dst: Path = migration['dst']
    old_pkg = migration['old_package']
    new_pkg = migration['new_package']

    if not src.exists():
        print(f"  ⚠️  Источник не найден: {src}")
        return False

    # Читаем файл построчно
    lines = src.read_text(encoding='utf-8').splitlines(keepends=True)
    new_lines = []
    package_replaced = False

    for line in lines:
        stripped = line.rstrip('\n').rstrip('\r')
        if stripped == old_pkg:
            new_lines.append(new_pkg + '\n')
            package_replaced = True
        else:
            new_lines.append(line)

    if not package_replaced:
        print(f"  ⚠️  Package не найден в {src}")
        return False

    # Создаём директорию и записываем
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(''.join(new_lines), encoding='utf-8')

    # Удаляем старый файл
    src.unlink()

    print(f"  ✓ {src.name}")
    print(f"    {old_pkg} → {new_pkg}")
    return True


def update_imports(replacement_config: dict):
    """Обновляет импорты в контроллере"""
    file_path: Path = replacement_config['file']
    replacements = replacement_config['replacements']

    if not file_path.exists():
        print(f"  ⚠️  Файл не найден: {file_path}")
        return False

    lines = file_path.read_text(encoding='utf-8').splitlines(keepends=True)
    new_lines = []
    changes_made = 0

    for line in lines:
        stripped = line.rstrip('\n').rstrip('\r')
        new_line = line
        for old_import, new_import in replacements:
            if stripped == old_import:
                new_line = new_import + '\n'
                changes_made += 1
                break
        new_lines.append(new_line)

    if changes_made == 0:
        print(f"  ℹ️  {file_path.name}: без изменений")
        return True

    file_path.write_text(''.join(new_lines), encoding='utf-8')
    print(f"  ✓ {file_path.name}: заменено {changes_made} импортов")
    return True


def main():
    print("=" * 80)
    print("🚀 ПЕРЕНОС ОСТАВШИХСЯ DTO ИЗ crypto-service В crypto-api")
    print("=" * 80)

    # Шаг 1: Перенос DTO
    print("\n📦 ШАГ 1: Перенос DTO")
    print("-" * 80)

    for migration in DTO_MIGRATIONS:
        migrate_dto(migration)

    # Шаг 2: Обновление импортов
    print("\n📝 ШАГ 2: Обновление импортов в контроллерах")
    print("-" * 80)

    for config in IMPORT_REPLACEMENTS:
        update_imports(config)

    print("\n" + "=" * 80)
    print("✅ ПЕРЕНОС ЗАВЕРШЁН")
    print("=" * 80)


if __name__ == '__main__':
    main()