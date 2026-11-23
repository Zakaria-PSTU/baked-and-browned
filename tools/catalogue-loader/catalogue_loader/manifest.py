"""Manifest loading and validation.

The manifest is JSON so the tool needs no third-party parser. Validation collects every problem
before reporting. Messages name a path and a rule, never the offending value, so a rejected
price or fee does not end up in a terminal log.

Money is written as a JSON *string* ("1200.00"): a JSON number would pass through a binary
float on the way in.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import time
from decimal import Decimal
from pathlib import Path
from typing import Any

from .errors import ManifestError

MANIFEST_VERSION = 1
DEFAULT_HORIZON_DAYS = 14
MAX_HORIZON_DAYS = 62  # the admin slot listing accepts at most 62 days

# Patterns and limits mirror docs/api-contract.yaml and the backend DTO validation.
SLUG_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
ZONE_CODE_RE = re.compile(r"^[A-Z0-9_]{2,60}$")
MONEY_RE = re.compile(r"^(0|[1-9][0-9]{0,9})(\.[0-9]{1,2})?$")  # NUMERIC(12,2), non-negative
TIME_RE = re.compile(r"^([01][0-9]|2[0-3]):([0-5][0-9])$")
PRODUCT_STATUSES = ("DRAFT", "ACTIVE", "ARCHIVED")
WEEKDAYS = {"mon": 0, "tue": 1, "wed": 2, "thu": 3, "fri": 4, "sat": 5, "sun": 6}
INT_MAX = 2_147_483_647

_TOP_KEYS = {"version", "categories", "products", "zones", "slotTemplate"}


@dataclass(frozen=True)
class CategorySpec:
    slug: str
    name: str
    sort_order: int
    active: bool


@dataclass(frozen=True)
class ProductSpec:
    slug: str
    name: str
    category_slug: str
    description: str | None
    base_price: Decimal
    min_lead_hours: int
    production_units: int
    cancellation_cutoff_hours: int
    status: str
    image_url: str | None


@dataclass(frozen=True)
class ZoneSpec:
    code: str
    name: str
    delivery_fee: Decimal | None
    min_order_amount: Decimal | None
    active: bool
    sort_order: int


@dataclass(frozen=True)
class WindowSpec:
    start: time
    end: time
    capacity_units: int
    active: bool


@dataclass(frozen=True)
class RuleSpec:
    weekdays: tuple[int, ...]  # Monday is 0
    windows: tuple[WindowSpec, ...]


@dataclass(frozen=True)
class SlotTemplate:
    horizon_days: int
    rules: tuple[RuleSpec, ...]


@dataclass(frozen=True)
class Manifest:
    categories: tuple[CategorySpec, ...]
    products: tuple[ProductSpec, ...]
    zones: tuple[ZoneSpec, ...]
    slot_template: SlotTemplate | None


class _Problems:
    def __init__(self) -> None:
        self.items: list[str] = []

    def add(self, path: str, rule: str) -> None:
        self.items.append(f"{path}: {rule}")


def load_manifest(path: Path) -> Manifest:
    """Read and validate a manifest file. Raises ManifestError listing every problem."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        raise ManifestError(["manifest file cannot be read"]) from None
    except UnicodeDecodeError:
        raise ManifestError(["manifest file is not valid UTF-8"]) from None
    return parse_manifest(text)


def parse_manifest(text: str) -> Manifest:
    duplicates: list[str] = []

    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        seen: dict[str, Any] = {}
        for key, value in pairs:
            if key in seen:
                duplicates.append(key)
            seen[key] = value
        return seen

    try:
        raw = json.loads(text, object_pairs_hook=reject_duplicate_keys)
    except json.JSONDecodeError as err:
        raise ManifestError([f"not valid JSON (line {err.lineno}, column {err.colno})"]) from None
    problems = _Problems()
    for key in sorted(set(duplicates)):
        problems.add("$", f"duplicate JSON key '{key}'")
    manifest = _parse_root(raw, problems)
    if problems.items or manifest is None:
        raise ManifestError(problems.items or ["manifest is invalid"])
    return manifest


# ---------------------------------------------------------------------------- helpers


def _object(
    value: Any, path: str, p: _Problems, required: set[str], optional: set[str]
) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        p.add(path, "must be an object")
        return None
    for key in value:
        if key not in required | optional:
            p.add(f"{path}.{key}", "unknown field")
    for key in sorted(required):
        if key not in value:
            p.add(f"{path}.{key}", "required")
    return value


def _string(
    obj: dict[str, Any],
    key: str,
    path: str,
    p: _Problems,
    *,
    min_len: int = 1,
    max_len: int,
    pattern: re.Pattern[str] | None = None,
) -> str | None:
    if key not in obj:
        return None
    value = obj[key]
    where = f"{path}.{key}"
    if not isinstance(value, str):
        p.add(where, "must be a string")
        return None
    if not min_len <= len(value.strip() if min_len else value) or len(value) > max_len:
        p.add(where, f"length must be {min_len} to {max_len}")
        return None
    if pattern is not None and not pattern.match(value):
        p.add(where, "does not match the required pattern")
        return None
    return value


def _integer(
    obj: dict[str, Any], key: str, path: str, p: _Problems, *, lo: int, hi: int, default: int | None
) -> int | None:
    if key not in obj:
        return default
    value = obj[key]
    where = f"{path}.{key}"
    if isinstance(value, bool) or not isinstance(value, int):
        p.add(where, "must be an integer")
        return None
    if not lo <= value <= hi:
        p.add(where, f"must be between {lo} and {hi}")
        return None
    return value


def _boolean(
    obj: dict[str, Any], key: str, path: str, p: _Problems, *, default: bool | None
) -> bool | None:
    if key not in obj:
        return default
    value = obj[key]
    if not isinstance(value, bool):
        p.add(f"{path}.{key}", "must be true or false")
        return None
    return value


def _money(
    obj: dict[str, Any], key: str, path: str, p: _Problems, *, nullable: bool
) -> Decimal | None:
    if key not in obj or (nullable and obj[key] is None):
        return None
    value = obj[key]
    where = f"{path}.{key}"
    if not isinstance(value, str):
        p.add(where, 'must be a string such as "100.00" (not a JSON number)')
        return None
    if not MONEY_RE.match(value):
        p.add(where, "must be a non-negative amount with at most 2 decimals and 10 integer digits")
        return None
    return Decimal(value).quantize(Decimal("0.01"))


def _list(value: Any, path: str, p: _Problems) -> list[Any]:
    if not isinstance(value, list):
        p.add(path, "must be a list")
        return []
    return value


# ---------------------------------------------------------------------------- sections


def _parse_root(raw: Any, p: _Problems) -> Manifest | None:
    root = _object(raw, "$", p, {"version"}, _TOP_KEYS - {"version"})
    if root is None:
        return None
    if "version" in root and root["version"] != MANIFEST_VERSION:
        p.add("$.version", f"must be {MANIFEST_VERSION}")
    if not ({"categories", "products", "zones", "slotTemplate"} & set(root)):
        p.add("$", "at least one of categories, products, zones or slotTemplate is required")
    categories = _parse_categories(root.get("categories", []), p)
    products = _parse_products(root.get("products", []), p)
    zones = _parse_zones(root.get("zones", []), p)
    template = _parse_template(root["slotTemplate"], p) if "slotTemplate" in root else None
    return Manifest(categories, products, zones, template)


def _parse_categories(raw: Any, p: _Problems) -> tuple[CategorySpec, ...]:
    out: list[CategorySpec] = []
    slugs: set[str] = set()
    names: set[str] = set()
    for i, item in enumerate(_list(raw, "$.categories", p)):
        path = f"$.categories[{i}]"
        obj = _object(item, path, p, {"slug", "name"}, {"sortOrder", "active"})
        if obj is None:
            continue
        slug = _string(obj, "slug", path, p, max_len=120, pattern=SLUG_RE)
        name = _string(obj, "name", path, p, min_len=2, max_len=100)
        sort_order = _integer(obj, "sortOrder", path, p, lo=-INT_MAX, hi=INT_MAX, default=0)
        active = _boolean(obj, "active", path, p, default=True)
        if slug is not None:
            if slug in slugs:
                p.add(f"{path}.slug", "duplicate slug in the manifest")
            slugs.add(slug)
        if name is not None:
            if name in names:
                p.add(f"{path}.name", "duplicate name in the manifest")
            names.add(name)
        if None not in (slug, name, sort_order, active):
            assert slug is not None and name is not None
            assert sort_order is not None and active is not None
            out.append(CategorySpec(slug, name.strip(), sort_order, active))
    return tuple(out)


def _parse_products(raw: Any, p: _Problems) -> tuple[ProductSpec, ...]:
    required = {"slug", "name", "category", "basePrice", "minLeadHours", "productionUnits"}
    optional = {"description", "cancellationCutoffHours", "status", "imageUrl"}
    out: list[ProductSpec] = []
    slugs: set[str] = set()
    for i, item in enumerate(_list(raw, "$.products", p)):
        path = f"$.products[{i}]"
        obj = _object(item, path, p, required, optional)
        if obj is None:
            continue
        slug = _string(obj, "slug", path, p, max_len=200, pattern=SLUG_RE)
        name = _string(obj, "name", path, p, max_len=180)
        category = _string(obj, "category", path, p, max_len=120, pattern=SLUG_RE)
        description = _optional_text(obj, "description", path, p, max_len=5000)
        price = _money(obj, "basePrice", path, p, nullable=False)
        lead = _integer(obj, "minLeadHours", path, p, lo=0, hi=100_000, default=None)
        units = _integer(obj, "productionUnits", path, p, lo=1, hi=INT_MAX, default=None)
        cutoff = _integer(obj, "cancellationCutoffHours", path, p, lo=0, hi=720, default=24)
        status = _string(obj, "status", path, p, max_len=10) if "status" in obj else "DRAFT"
        if status is not None and status not in PRODUCT_STATUSES:
            p.add(f"{path}.status", f"must be one of {', '.join(PRODUCT_STATUSES)}")
            status = None
        image = _optional_text(obj, "imageUrl", path, p, max_len=2048)
        if image is not None and (not image.startswith("https://") or re.search(r"\s", image)):
            p.add(f"{path}.imageUrl", "must be an https URL without whitespace")
            image = None
            image_ok = False
        else:
            image_ok = True
        if slug is not None:
            if slug in slugs:
                p.add(f"{path}.slug", "duplicate slug in the manifest")
            slugs.add(slug)
        values = (slug, name, category, price, lead, units, cutoff, status)
        if None not in values and image_ok:
            assert slug is not None and name is not None and category is not None
            assert price is not None and lead is not None and units is not None
            assert cutoff is not None and status is not None
            out.append(
                ProductSpec(
                    slug, name.strip(), category, description, price, lead, units, cutoff, status, image
                )
            )
    return tuple(out)


def _optional_text(
    obj: dict[str, Any], key: str, path: str, p: _Problems, *, max_len: int
) -> str | None:
    if key not in obj or obj[key] is None:
        return None
    text = _string(obj, key, path, p, min_len=0, max_len=max_len)
    if text is None or text.strip() == "":
        return None  # the backend stores a blank description as null
    return text.strip()


def _parse_zones(raw: Any, p: _Problems) -> tuple[ZoneSpec, ...]:
    out: list[ZoneSpec] = []
    codes: set[str] = set()
    names: set[str] = set()
    for i, item in enumerate(_list(raw, "$.zones", p)):
        path = f"$.zones[{i}]"
        obj = _object(
            item, path, p, {"code", "name", "active"}, {"deliveryFee", "minOrderAmount", "sortOrder"}
        )
        if obj is None:
            continue
        code = _string(obj, "code", path, p, max_len=60, pattern=ZONE_CODE_RE)
        name = _string(obj, "name", path, p, min_len=2, max_len=120)
        fee = _money(obj, "deliveryFee", path, p, nullable=True)
        minimum = _money(obj, "minOrderAmount", path, p, nullable=True)
        active = _boolean(obj, "active", path, p, default=None)
        sort_order = _integer(obj, "sortOrder", path, p, lo=-INT_MAX, hi=INT_MAX, default=0)
        if active is True and fee is None:
            p.add(f"{path}.deliveryFee", "required to activate a zone")
        if code is not None:
            if code in codes:
                p.add(f"{path}.code", "duplicate code in the manifest")
            codes.add(code)
        if name is not None:
            if name in names:
                p.add(f"{path}.name", "duplicate name in the manifest")
            names.add(name)
        if None not in (code, name, active, sort_order):
            assert code is not None and name is not None
            assert active is not None and sort_order is not None
            out.append(ZoneSpec(code, name.strip(), fee, minimum, active, sort_order))
    return tuple(out)


def _parse_template(raw: Any, p: _Problems) -> SlotTemplate | None:
    path = "$.slotTemplate"
    obj = _object(raw, path, p, {"rules"}, {"horizonDays"})
    if obj is None:
        return None
    horizon = _integer(
        obj, "horizonDays", path, p, lo=1, hi=MAX_HORIZON_DAYS, default=DEFAULT_HORIZON_DAYS
    )
    rules: list[RuleSpec] = []
    seen: set[tuple[int, time, time]] = set()
    rule_list = _list(obj.get("rules", []), f"{path}.rules", p) if "rules" in obj else []
    if "rules" in obj and not rule_list:
        p.add(f"{path}.rules", "must not be empty")
    for i, item in enumerate(rule_list):
        rpath = f"{path}.rules[{i}]"
        robj = _object(item, rpath, p, {"weekdays", "windows"}, set())
        if robj is None:
            continue
        weekdays = _parse_weekdays(robj.get("weekdays"), f"{rpath}.weekdays", p)
        windows = _parse_windows(robj.get("windows"), f"{rpath}.windows", p)
        if weekdays is None or windows is None:
            continue
        for day in weekdays:
            for window in windows:
                key = (day, window.start, window.end)
                if key in seen:
                    p.add(rpath, "the same weekday and window appears more than once")
                seen.add(key)
        rules.append(RuleSpec(weekdays, windows))
    if horizon is None:
        return None
    return SlotTemplate(horizon, tuple(rules))


def _parse_weekdays(raw: Any, path: str, p: _Problems) -> tuple[int, ...] | None:
    days = _list(raw, path, p)
    if not days:
        if isinstance(raw, list):
            p.add(path, "must not be empty")
        return None
    out: list[int] = []
    for i, day in enumerate(days):
        if not isinstance(day, str) or day not in WEEKDAYS:
            p.add(f"{path}[{i}]", "must be one of mon, tue, wed, thu, fri, sat, sun")
            return None
        if WEEKDAYS[day] in out:
            p.add(f"{path}[{i}]", "duplicate weekday")
            return None
        out.append(WEEKDAYS[day])
    return tuple(out)


def _parse_windows(raw: Any, path: str, p: _Problems) -> tuple[WindowSpec, ...] | None:
    items = _list(raw, path, p)
    if not items:
        if isinstance(raw, list):
            p.add(path, "must not be empty")
        return None
    out: list[WindowSpec] = []
    ok = True
    for i, item in enumerate(items):
        wpath = f"{path}[{i}]"
        obj = _object(item, wpath, p, {"start", "end", "capacityUnits"}, {"active"})
        if obj is None:
            ok = False
            continue
        start = _clock(obj, "start", wpath, p)
        end = _clock(obj, "end", wpath, p)
        capacity = _integer(obj, "capacityUnits", wpath, p, lo=1, hi=INT_MAX, default=None)
        active = _boolean(obj, "active", wpath, p, default=True)
        if start is not None and end is not None and not start < end:
            p.add(wpath, "end must be after start on the same day")
            ok = False
        if None in (start, end, capacity, active):
            ok = False
            continue
        assert start is not None and end is not None
        assert capacity is not None and active is not None
        out.append(WindowSpec(start, end, capacity, active))
    return tuple(out) if ok else None


def _clock(obj: dict[str, Any], key: str, path: str, p: _Problems) -> time | None:
    text = _string(obj, key, path, p, max_len=5, pattern=TIME_RE)
    if text is None:
        return None
    hours, minutes = text.split(":")
    return time(int(hours), int(minutes))
