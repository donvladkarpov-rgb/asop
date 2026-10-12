#!/usr/bin/env python3
"""
Dev-заглушка фискального посредника (ОФД) для проверки фискализации end-to-end (промпт 017).

Поднимается контейнером `mock-fiscal` (см. docker-compose.yml) и намеренно повторяет
пути Bifit-адаптера (примерные URL — prompts/my/prompt_017.md):

    POST /processing-api/protected/documents/registration/receipts/token
         headers: Connector-Token, Idempotency-Key (receiptId)
         тело: {type:"SALE", items:[...], total, payments:{CARD:...}, ...}
         → {"status":"OK", "receiptNumber": ..., "fn": ..., "fs": ...}

Полученный кассовый чек сохраняется в памяти прямо в терминале mock-ОФД по receiptId
и показывается по URL страницы чека (как в проде у Bifit):

    GET /processing-api/receipts/{receiptId}  → HTML-страница чека (200) / 404

Dev-индекс сохранённых чеков: GET /receipts (список receiptId). GET /health — для healthcheck.
Только stdlib, порт MOCK_PORT (default 8089).
"""
import html
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "8089"))

# Хранилище чеков в оперативной памяти: {receiptId_lower: {...}}
RECEIPTS = {}


def _now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Handler(BaseHTTPRequestHandler):
    # --- helpers -----------------------------------------------------------
    def _send_json(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, code, html_text):
        body = html_text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # --- GET ---------------------------------------------------------------
    def do_GET(self):  # noqa: N802
        path = self.path.rstrip("/") or "/"
        if path == "/health":
            self._send_json(200, {"status": "OK", "receipts": len(RECEIPTS)})
            return
        if path == "/receipts":
            self._send_html(200, self._index_page())
            return
        # GET /processing-api/receipts/{receiptId} — страница чека (URL из QR).
        prefix = "/processing-api/receipts/"
        if path.startswith(prefix):
            receipt = RECEIPTS.get(path[len(prefix):].lower())
            if receipt is not None:
                self._send_html(200, self._receipt_page(receipt))
            else:
                self._send_html(404, self._not_found_page(path))
            return
        self._send_json(404, {"status": "NOT_FOUND"})

    # --- POST (регистрация чека) -------------------------------------------
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b""
        idem = (self.headers.get("Idempotency-Key") or "").strip()
        try:
            parsed = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            parsed = {}
        receipt_id = (idem or uuid.uuid4().hex).lower()
        receipt = {
            "receipt_id": receipt_id,
            "path": self.path,
            "body": parsed,
            "receipt_number": uuid.uuid4().hex[:8].upper(),
            "fn": "9999078900000001",
            "fs": uuid.uuid4().hex[:10].upper(),
            "registered_at": _now_iso(),
        }
        RECEIPTS[receipt_id] = receipt
        sys.stdout.write(
            "[mock-fiscal] receipt idem=%s path=%s stored=%d body=%s\n"
            % (receipt_id, self.path, len(RECEIPTS), json.dumps(parsed, ensure_ascii=False)[:500])
        )
        sys.stdout.flush()
        self._send_json(
            200,
            {
                "status": "OK",
                "receiptNumber": receipt["receipt_number"],
                "fn": receipt["fn"],
                "fs": receipt["fs"],
            },
        )

    # --- HTML-страницы -----------------------------------------------------
    @staticmethod
    def _page(shell, title):
        return (
            "<!DOCTYPE html><html lang='ru'><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width, initial-scale=1'>"
            "<title>%s</title><style>"
            "body{font-family:monospace;background:#f5f5f5;margin:0;padding:24px}"
            "main{max-width:420px;margin:0 auto;background:#fff;padding:24px;"
            "border:1px dashed #999;box-shadow:0 2px 8px rgba(0,0,0,.08)}"
            "h1{font-size:18px;text-align:center;margin:0 0 4px}"
            "h2{font-size:14px;text-align:center;color:#555;margin:0 0 16px;font-weight:400}"
            "table{width:100%%;border-collapse:collapse;font-size:13px}"
            "td{padding:3px 0;vertical-align:top}"
            "td.r{text-align:right;white-space:nowrap}"
            ".sep{border-top:1px dashed #999;margin:10px 0}"
            ".dim{color:#777;font-size:12px}"
            ".err{color:#b00;text-align:center}"
            "</style></head><body><main>%s</main></body></html>"
        ) % (html.escape(title), shell)

    def _index_page(self):
        rows = "".join(
            "<tr><td><a href='/processing-api/receipts/%s'>%s</a></td>"
            "<td class='dim'>%s</td></tr>"
            % (html.escape(r["receipt_id"]), html.escape(r["receipt_id"]),
               html.escape(r["registered_at"]))
            for r in sorted(RECEIPTS.values(), key=lambda x: x["registered_at"], reverse=True)
        ) or "<tr><td class='dim'>Нет сохранённых чеков</td></tr>"
        shell = (
            "<h1>Mock-ОФД</h1><h2>Сохранённые чеки (в памяти): %d</h2>"
            "<table>%s</table>" % (len(RECEIPTS), rows)
        )
        return self._page(shell, "Mock-ОФД — чеки")

    def _receipt_page(self, r):
        body = r["body"] or {}
        items = body.get("items") or []
        item_rows = "".join(
            "<tr><td>%s</td><td class='r'>%s &times; %s</td><td class='r'>%.2f</td></tr>"
            % (html.escape(str(i.get("name", ""))), _fmt(i.get("price")),
               html.escape(str(i.get("quantity", "1"))), float(i.get("total", 0) or 0))
            for i in items
        )
        payments = body.get("payments") or {}
        pay_rows = "".join(
            "<tr><td>Оплата %s</td><td class='r'>%.2f</td></tr>"
            % (html.escape(str(k)), float(v or 0)) for k, v in payments.items()
        )
        cashier = (body.get("cashier") or {}).get("name", "")
        address = body.get("paymentAddress", "")
        total = _fmt(body.get("total"))
        tax = body.get("taxSystem", "")
        tip = body.get("type", "")
        shell = (
            "<h1>КАССОВЫЙ ЧЕК</h1><h2>mock-fiscal (dev)</h2>"
            "<table>"
            "<tr><td>Тип</td><td class='r'>%s</td></tr>"
            "<tr><td>Налог. система</td><td class='r'>%s</td></tr>"
            "<tr><td>Кассир</td><td class='r'>%s</td></tr>"
            "<tr><td>Адрес</td><td class='r'>%s</td></tr>"
            "<tr><td>Время</td><td class='r'>%s</td></tr>"
            "</table><div class='sep'></div><table>%s</table><div class='sep'></div>"
            "<table>%s</table><div class='sep'></div>"
            "<table>"
            "<tr><td><b>ИТОГО</b></td><td class='r'><b>%s</b></td></tr>"
            "</table><div class='sep'></div>"
            "<table>"
            "<tr><td class='dim'>Чек №</td><td class='r'>%s</td></tr>"
            "<tr><td class='dim'>ФН</td><td class='r'>%s</td></tr>"
            "<tr><td class='dim'>ФП</td><td class='r'>%s</td></tr>"
            "<tr><td class='dim'>Idempotency-Key</td><td class='r'>%s</td></tr>"
            "</table>"
            % (html.escape(tip), html.escape(tax), html.escape(cashier), html.escape(address),
               html.escape(r["registered_at"]), item_rows, pay_rows, total,
               html.escape(r["receipt_number"]), html.escape(r["fn"]), html.escape(r["fs"]),
               html.escape(r["receipt_id"]))
        )
        return self._page(shell, "Кассовый чек %s" % r["receipt_id"])

    def _not_found_page(self, path):
        shell = "<h1 class='err'>ЧЕК НЕ НАЙДЕН</h1><h2>404</h2><p class='dim'>%s</p>" % html.escape(path)
        return self._page(shell, "404 — чек не найден")

    def log_message(self, fmt, *args):  # silence default per-request logging
        return


def _fmt(value):
    try:
        return "%.2f" % float(value if value is not None else 0)
    except (TypeError, ValueError):
        return "0.00"


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()