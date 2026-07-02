#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).parent.resolve()
BACKEND = ROOT / 'backend'

# Ищем все файлы с Request/Response
print("🔍 Поиск DTO файлов в backend/:\n")

for kt_file in BACKEND.rglob('*.kt'):
    if 'Request' in kt_file.name or 'Response' in kt_file.name:
        rel_path = kt_file.relative_to(ROOT)
        print(f"  {rel_path}")