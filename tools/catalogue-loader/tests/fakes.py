"""An in-memory stand-in for the admin API, for tests only. All data is fictional.

It mirrors the rules the real backend enforces (checked against the controllers and services on
2026-11-23): unique natural keys with 409 on a duplicate, 422 for an invalid or past slot or an
active zone without a fee, pages capped at 100, slot listings keyed on the Dhaka calendar day,
and bearer authentication with an ADMIN-only role. It also records every call, so tests can
prove that a dry run wrote nothing and that no update or delete was ever issued.

Faults can be injected: an HTTP error, a lost answer after the write committed, a lost request,
a one-shot hook that plays a concurrent writer, and a server clock that disagrees with the
client's.
"""

from __future__ import annotations

import json
import urllib.parse
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Any

from catalogue_loader.client import LOGIN_PATH, HttpRequest, HttpResponse, JsonObject
from catalogue_loader.errors import TransportError
from catalogue_loader.slots import DHAKA, format_instant, parse_instant

ADMIN_TOKEN = "admin-token-FAKE-0001"
CUSTOMER_TOKEN = "customer-token-FAKE-0002"
IDENTIFIER = "operator.fake@example.test"
PASSWORD = "password-FAKE-0003"


@dataclass
class _Fault:
    method: str
    path: str
    status: int
    code: str
    remaining: int
    skip: int


def _json_default(value: object) -> float:
    if isinstance(value, Decimal):
        return float(value)
    raise TypeError("not serializable")


class FakeApi:
    def __init__(self, now: datetime) -> None:
        self.now = now  # the server's clock
        self.categories: dict[str, JsonObject] = {}
        self.products: dict[str, JsonObject] = {}
        self.zones: dict[str, JsonObject] = {}
        self.slots: dict[str, JsonObject] = {}
        self.calls: list[tuple[str, str]] = []
        self.echo_secrets_in_errors = False
        self._faults: list[_Fault] = []
        self._hooks: dict[str, Callable[[], None]] = {}
        self._lost_after: dict[str, int] = {}
        self._lost_before: dict[str, int] = {}

    # ------------------------------------------------------------------ test controls

    def fail(self, method: str, path: str, status: int, code: str, *, times: int = 1, skip: int = 0) -> None:
        self._faults.append(_Fault(method, path, status, code, times, skip))

    def clear_faults(self) -> None:
        self._faults.clear()
        self._lost_after.clear()
        self._lost_before.clear()

    def before_post(self, path: str, hook: Callable[[], None]) -> None:
        """Run ``hook`` once, just before the next POST to ``path`` (a concurrent writer)."""
        self._hooks[path] = hook

    def lose_answer_after_commit(self, path: str, times: int = 1) -> None:
        self._lost_after[path] = times

    def lose_request_before_commit(self, path: str, times: int = 1) -> None:
        self._lost_before[path] = times

    @property
    def writes(self) -> list[tuple[str, str]]:
        return [c for c in self.calls if c[0] != "GET" and c[1] != LOGIN_PATH]

    # ------------------------------------------------------------------ direct seeding

    def add_category(self, slug: str, name: str, sort_order: int = 0, active: bool = True) -> str:
        record: JsonObject = {
            "id": str(uuid.uuid4()), "slug": slug, "name": name, "sortOrder": sort_order, "active": active,
        }
        self.categories[record["id"]] = record
        return str(record["id"])

    def add_zone(self, code: str, name: str, fee: str | None, active: bool, minimum: str | None = None,
                 sort_order: int = 0) -> None:
        record: JsonObject = {
            "id": str(uuid.uuid4()), "code": code, "name": name,
            "deliveryFee": Decimal(fee) if fee is not None else None,
            "minOrderAmount": Decimal(minimum) if minimum is not None else None,
            "active": active, "sortOrder": sort_order,
        }
        self.zones[record["id"]] = record

    def add_product(self, slug: str, name: str, category_id: str, price: str, **overrides: Any) -> None:
        record: JsonObject = {
            "id": str(uuid.uuid4()), "slug": slug, "name": name, "categoryId": category_id,
            "description": None, "imageUrl": None, "basePrice": Decimal(price), "currency": "BDT",
            "minLeadHours": 24, "cancellationCutoffHours": 24, "productionUnits": 1, "status": "DRAFT",
        }
        record.update(overrides)
        self.products[record["id"]] = record

    def add_slot(self, start: datetime, end: datetime, capacity: int, active: bool = True) -> None:
        record: JsonObject = {
            "id": str(uuid.uuid4()), "startAt": format_instant(start), "endAt": format_instant(end),
            "capacityUnits": capacity, "reservedUnits": 0, "remainingUnits": capacity, "active": active,
        }
        self.slots[record["id"]] = record

    # ------------------------------------------------------------------ the transport

    def transport(self, request: HttpRequest) -> HttpResponse:
        parts = urllib.parse.urlsplit(request.url)
        path = parts.path
        query = dict(urllib.parse.parse_qsl(parts.query))
        self.calls.append((request.method, path))
        for fault in self._faults:
            if fault.method == request.method and fault.path == path:
                if fault.skip > 0:
                    fault.skip -= 1
                    continue
                if fault.remaining > 0:
                    fault.remaining -= 1
                    return self._error(fault.status, fault.code, request)
        if request.method == "POST" and self._lost_before.get(path, 0) > 0:
            self._lost_before[path] -= 1
            raise TransportError("lost before commit")
        body = self._body(request)
        if request.method == "POST" and path == LOGIN_PATH:
            return self._login(body, request)
        denied = self._authorize(request)
        if denied is not None:
            return denied
        if request.method == "POST":
            hook = self._hooks.pop(path, None)
            if hook is not None:
                hook()
        response = self._route(request.method, path, query, body, request)
        if request.method == "POST" and self._lost_after.get(path, 0) > 0:
            self._lost_after[path] -= 1
            raise TransportError("lost after commit")
        return response

    # ------------------------------------------------------------------ routing

    def _route(self, method: str, path: str, query: dict[str, str], body: JsonObject | None,
               request: HttpRequest) -> HttpResponse:
        base = "/api/v1/admin"
        if (method, path) == ("GET", f"{base}/categories"):
            return self._ok(list(self.categories.values()))
        if (method, path) == ("POST", f"{base}/categories"):
            return self._create_category(body or {}, request)
        if (method, path) == ("GET", f"{base}/products"):
            return self._list_products(query)
        if (method, path) == ("POST", f"{base}/products"):
            return self._create_product(body or {}, request)
        if (method, path) == ("GET", f"{base}/delivery-zones"):
            return self._ok(list(self.zones.values()))
        if (method, path) == ("POST", f"{base}/delivery-zones"):
            return self._create_zone(body or {}, request)
        if (method, path) == ("GET", f"{base}/delivery-slots"):
            return self._list_slots(query, request)
        if (method, path) == ("POST", f"{base}/delivery-slots"):
            return self._create_slot(body or {}, request)
        return self._error(404, "NOT_FOUND", request)  # includes any PATCH, PUT or DELETE

    def _create_category(self, body: JsonObject, request: HttpRequest) -> HttpResponse:
        slug, name = body.get("slug"), body.get("name")
        if any(c["slug"] == slug or c["name"] == name for c in self.categories.values()):
            return self._error(409, "CATEGORY_EXISTS", request)
        record: JsonObject = {
            "id": str(uuid.uuid4()), "slug": slug, "name": name,
            "sortOrder": body.get("sortOrder", 0), "active": body.get("active", True),
        }
        self.categories[record["id"]] = record
        return self._ok(record, 201)

    def _create_product(self, body: JsonObject, request: HttpRequest) -> HttpResponse:
        if body.get("categoryId") not in self.categories:
            return self._error(422, "UNKNOWN_CATEGORY", request)
        if any(p["slug"] == body.get("slug") for p in self.products.values()):
            return self._error(409, "SLUG_TAKEN", request)
        description = body.get("description")
        record: JsonObject = {
            "id": str(uuid.uuid4()), "slug": body["slug"], "name": body["name"],
            "categoryId": body["categoryId"],
            "description": description.strip() or None if isinstance(description, str) else None,
            "imageUrl": body.get("imageUrl"),
            "basePrice": Decimal(str(body["basePrice"])).quantize(Decimal("0.01")), "currency": "BDT",
            "minLeadHours": body["minLeadHours"], "productionUnits": body["productionUnits"],
            "cancellationCutoffHours": body.get("cancellationCutoffHours", 24),
            "status": body.get("status", "DRAFT"),
        }
        self.products[record["id"]] = record
        return self._ok(record, 201)

    def _list_products(self, query: dict[str, str]) -> HttpResponse:
        page, size = int(query.get("page", "0")), min(int(query.get("size", "20")), 100)
        items = list(self.products.values())
        content = items[page * size:(page + 1) * size]
        return self._ok({"content": content, "page": page, "size": size, "totalElements": len(items)})

    def _create_zone(self, body: JsonObject, request: HttpRequest) -> HttpResponse:
        if any(z["code"] == body.get("code") or z["name"] == body.get("name") for z in self.zones.values()):
            return self._error(409, "ZONE_EXISTS", request)
        if body.get("active") is True and body.get("deliveryFee") is None:
            return self._error(422, "ZONE_FEE_REQUIRED", request)
        def money(key: str) -> Decimal | None:
            value = body.get(key)
            return None if value is None else Decimal(str(value)).quantize(Decimal("0.01"))
        record: JsonObject = {
            "id": str(uuid.uuid4()), "code": body["code"], "name": body["name"],
            "deliveryFee": money("deliveryFee"), "minOrderAmount": money("minOrderAmount"),
            "active": body["active"], "sortOrder": body.get("sortOrder", 0),
        }
        self.zones[record["id"]] = record
        return self._ok(record, 201)

    def _create_slot(self, body: JsonObject, request: HttpRequest) -> HttpResponse:
        start, end = parse_instant(str(body["startAt"])), parse_instant(str(body["endAt"]))
        if not end > start:
            return self._error(422, "INVALID_SLOT", request)
        if not start > self.now:
            return self._error(422, "SLOT_IN_PAST", request)
        if any(s["startAt"] == format_instant(start) and s["endAt"] == format_instant(end)
               for s in self.slots.values()):
            return self._error(409, "SLOT_EXISTS", request)
        capacity = body["capacityUnits"]
        record: JsonObject = {
            "id": str(uuid.uuid4()), "startAt": format_instant(start), "endAt": format_instant(end),
            "capacityUnits": capacity, "reservedUnits": 0, "remainingUnits": capacity,
            "active": body.get("active", True),
        }
        self.slots[record["id"]] = record
        return self._ok(record, 201)

    def _list_slots(self, query: dict[str, str], request: HttpRequest) -> HttpResponse:
        first = datetime.fromisoformat(query["from"]).replace(tzinfo=DHAKA)
        last = datetime.fromisoformat(query["to"]).replace(tzinfo=DHAKA)
        if last < first or (last - first) > timedelta(days=62):
            return self._error(422, "INVALID_RANGE", request)
        lo = first.astimezone(timezone.utc)
        hi = (last + timedelta(days=1)).astimezone(timezone.utc)
        return self._ok([s for s in self.slots.values() if lo <= parse_instant(s["startAt"]) < hi])

    # ------------------------------------------------------------------ plumbing

    def _login(self, body: JsonObject | None, request: HttpRequest) -> HttpResponse:
        if body and body.get("identifier") == IDENTIFIER and body.get("password") == PASSWORD:
            return self._ok({"accessToken": ADMIN_TOKEN, "tokenType": "Bearer", "expiresIn": 900, "user": {}})
        return self._error(401, "INVALID_CREDENTIALS", request)

    def _authorize(self, request: HttpRequest) -> HttpResponse | None:
        header = request.headers.get("Authorization", "")
        token = header[7:] if header.startswith("Bearer ") else ""
        if token == ADMIN_TOKEN:
            return None
        if token == CUSTOMER_TOKEN:
            return self._error(403, "FORBIDDEN", request)
        return self._error(401, "UNAUTHORIZED", request)

    @staticmethod
    def _body(request: HttpRequest) -> JsonObject | None:
        if not request.body:
            return None
        data = json.loads(request.body.decode("utf-8"), parse_float=Decimal)
        return data if isinstance(data, dict) else None

    def _ok(self, data: Any, status: int = 200) -> HttpResponse:
        return HttpResponse(status, json.dumps(data, default=_json_default).encode("utf-8"))

    def _error(self, status: int, code: str, request: HttpRequest) -> HttpResponse:
        detail = "rejected"
        if self.echo_secrets_in_errors:
            # A hostile or careless server that reflects what it was sent. The loader must not
            # relay any of it.
            detail = f"{request.headers.get('Authorization', '')} {(request.body or b'').decode('utf-8')}"
        problem = {"type": "about:blank", "title": "Error", "status": status, "detail": detail, "code": code}
        return HttpResponse(status, json.dumps(problem).encode("utf-8"))
