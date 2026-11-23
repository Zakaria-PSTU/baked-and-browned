"""HTTP client for the admin API: read, create, and nothing else.

The client has no update or delete method. That is deliberate: the backend's PATCH endpoints
replace a whole record and carry no version, so an update cannot be made safe against a
concurrent edit without a backend change (see README, "Why create-only").

Dry-run safety is enforced here as well as in the planner: a client built with
``allow_writes=False`` raises ``WriteBlockedError`` on any POST except the login call, so a
planner bug cannot turn a dry run into a write.
"""

from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from http.client import HTTPMessage
from typing import IO, Any

from .errors import ApiError, AuthError, ProtocolError, TransportError, WriteBlockedError

JsonObject = dict[str, Any]

LOGIN_PATH = "/api/v1/auth/login"
MAX_RESPONSE_BYTES = 16 * 1024 * 1024
PAGE_SIZE = 100  # the API's maximum
MAX_PAGES = 1000
DEFAULT_TIMEOUT = 30.0
_ERROR_CODE_RE = re.compile(r"^[A-Z0-9_]{1,60}$")


@dataclass(frozen=True)
class HttpRequest:
    method: str
    url: str
    headers: Mapping[str, str]
    body: bytes | None
    timeout: float


@dataclass(frozen=True)
class HttpResponse:
    status: int
    body: bytes


Transport = Callable[[HttpRequest], HttpResponse]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Never follow a redirect: it could carry the bearer token to another host."""

    def redirect_request(
        self,
        req: urllib.request.Request,
        fp: IO[bytes],
        code: int,
        msg: str,
        headers: HTTPMessage,
        newurl: str,
    ) -> urllib.request.Request | None:
        return None


def urllib_transport(request: HttpRequest) -> HttpResponse:
    """The real transport. Certificates are verified (the default); redirects are refused."""
    req = urllib.request.Request(
        request.url, data=request.body, method=request.method, headers=dict(request.headers)
    )
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        with opener.open(req, timeout=request.timeout) as response:
            return HttpResponse(response.status, response.read(MAX_RESPONSE_BYTES))
    except urllib.error.HTTPError as err:
        try:
            body = err.read(MAX_RESPONSE_BYTES)
        finally:
            err.close()
        return HttpResponse(err.code, body)
    except (urllib.error.URLError, TimeoutError, OSError):
        # The message could name the host; the operator already knows the target.
        raise TransportError("the request did not complete") from None


def _json_default(value: object) -> float:
    if isinstance(value, Decimal):
        # Amounts are validated to at most 12 significant digits, which a double holds exactly.
        return float(value)
    raise TypeError("value is not JSON serializable")


def encode_body(payload: JsonObject) -> bytes:
    return json.dumps(payload, default=_json_default, separators=(",", ":")).encode("utf-8")


def decode_body(body: bytes) -> Any:
    """Parse a response; decimals stay exact because floats are read as ``Decimal``."""
    try:
        return json.loads(body.decode("utf-8"), parse_float=Decimal)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ProtocolError("the API answered with a body that is not JSON") from None


def error_code(body: bytes, status: int) -> str:
    """The API's machine code (problem+json ``code``). Messages are never kept."""
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return f"HTTP_{status}"
    code = data.get("code") if isinstance(data, dict) else None
    if isinstance(code, str) and _ERROR_CODE_RE.match(code):
        return code
    return f"HTTP_{status}"


class ApiClient:
    def __init__(
        self,
        base_url: str,
        transport: Transport,
        *,
        token: str | None = None,
        allow_writes: bool = False,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._transport = transport
        self._token = token
        self._allow_writes = allow_writes
        self._timeout = timeout

    # ------------------------------------------------------------------ authentication

    def login(self, identifier: str, password: str) -> str:
        """Exchange credentials for a short-lived access token. Raises AuthError."""
        try:
            data = self._request("POST", LOGIN_PATH, body={"identifier": identifier, "password": password})
        except ApiError as err:
            if err.status in (400, 401, 403, 422, 429):
                raise AuthError(f"login was rejected ({err.code})") from None
            raise
        token = data.get("accessToken") if isinstance(data, dict) else None
        if not isinstance(token, str) or not token:
            raise ProtocolError("the login answer has no access token")
        self._token = token
        return token

    # ------------------------------------------------------------------ reads

    def list_categories(self) -> list[JsonObject]:
        return self._list("/api/v1/admin/categories")

    def list_zones(self) -> list[JsonObject]:
        return self._list("/api/v1/admin/delivery-zones")

    def list_slots(self, date_from: date, date_to: date) -> list[JsonObject]:
        query = {"from": date_from.isoformat(), "to": date_to.isoformat()}
        return self._list("/api/v1/admin/delivery-slots", query)

    def list_products(self) -> list[JsonObject]:
        """Every product, all statuses, across all pages (the API caps a page at 100)."""
        items: dict[str, JsonObject] = {}
        total = 0
        for page in range(MAX_PAGES):
            data = self._request(
                "GET", "/api/v1/admin/products", query={"page": str(page), "size": str(PAGE_SIZE)}
            )
            content, total = _page(data)
            for item in content:
                items[_identity(item)] = item  # a record that moved between pages counts once
            if not content or (page + 1) * PAGE_SIZE >= total:
                break
        else:
            raise ProtocolError("the product list has too many pages")
        # Records added or removed while paging can make the count differ from ``total``.
        # That is tolerated: every create is guarded by a 409 re-read.
        return list(items.values())

    # ------------------------------------------------------------------ creates

    def create_category(self, payload: JsonObject) -> JsonObject:
        return self._create("/api/v1/admin/categories", payload)

    def create_product(self, payload: JsonObject) -> JsonObject:
        return self._create("/api/v1/admin/products", payload)

    def create_zone(self, payload: JsonObject) -> JsonObject:
        return self._create("/api/v1/admin/delivery-zones", payload)

    def create_slot(self, payload: JsonObject) -> JsonObject:
        return self._create("/api/v1/admin/delivery-slots", payload)

    # ------------------------------------------------------------------ internals

    def _list(self, path: str, query: Mapping[str, str] | None = None) -> list[JsonObject]:
        data = self._request("GET", path, query=query)
        if not isinstance(data, list) or not all(isinstance(item, dict) for item in data):
            raise ProtocolError("a list endpoint did not answer with a list of objects")
        return data

    def _create(self, path: str, payload: JsonObject) -> JsonObject:
        data = self._request("POST", path, body=payload)
        if not isinstance(data, dict):
            raise ProtocolError("a create endpoint did not answer with an object")
        return data

    def _request(
        self,
        method: str,
        path: str,
        *,
        query: Mapping[str, str] | None = None,
        body: JsonObject | None = None,
    ) -> Any:
        if method != "GET" and path != LOGIN_PATH and not self._allow_writes:
            raise WriteBlockedError("this client is read-only (dry run)")
        url = self._base_url + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        headers = {"Accept": "application/json"}
        if self._token is not None and path != LOGIN_PATH:
            headers["Authorization"] = "Bearer " + self._token
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = encode_body(body)
        response = self._transport(HttpRequest(method, url, headers, data, self._timeout))
        if 200 <= response.status < 300:
            return decode_body(response.body) if response.body else None
        raise ApiError(response.status, error_code(response.body, response.status))


def _page(data: Any) -> tuple[list[JsonObject], int]:
    if not isinstance(data, dict):
        raise ProtocolError("a page was not an object")
    content = data.get("content")
    total = data.get("totalElements")
    if (
        not isinstance(content, list)
        or not all(isinstance(item, dict) for item in content)
        or isinstance(total, bool)
        or not isinstance(total, int)
    ):
        raise ProtocolError("a page does not have the expected content and totalElements")
    return content, total


def _identity(item: JsonObject) -> str:
    value = item.get("id")
    if not isinstance(value, str):
        raise ProtocolError("a record has no id")
    return value
