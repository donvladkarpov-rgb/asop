#!/usr/bin/env python3
"""
Dev-заглушка фискального посредника (ОФД) для проверки фискализации end-to-end (промпт 017).

Поднимается контейнером `mock-fiscal` (см. docker-compose.yml) и намеренно повторяет
registration-path Bifit-адаптера:
    POST /processing-api/protected/documents/registration/receipts/token
    headers: Connector-Token, Idempotency-Key
    → {"status":"OK", "receiptNumber": ..., "fn": ..., "fs": ...}

Также отдаёт GET /health для healthcheck. Только stdlib, порт MOCK_PORT (default 8089).
"""
import json
import os
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "8089"))


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/") == "/health":
            self._send(200, {"status": "OK"})
        else:
            self._send(404, {"status": "NOT_FOUND"})

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b""
        idem = self.headers.get("Idempotency-Key", "-")
        try:
            parsed = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            parsed = {}
        receipt_number = uuid.uuid4().hex[:8].upper()
        sys.stdout.write(
            "[mock-fiscal] receipt idem=%s path=%s body=%s\n"
            % (idem, self.path, json.dumps(parsed, ensure_ascii=False)[:500])
        )
        sys.stdout.flush()
        self._send(
            200,
            {
                "status": "OK",
                "receiptNumber": receipt_number,
                "fn": "9999078900000001",
                "fs": uuid.uuid4().hex[:10],
            },
        )

    def log_message(self, fmt, *args):  # silence default per-request logging
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
