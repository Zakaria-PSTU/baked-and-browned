"""Planning and applying: read before create, create-only, stop on the first failure.

The planner reads the current state through read-only calls and decides, per record and by its
natural key, one of:

* CREATE  - no record has the key.
* NOOP    - a record has the key and already matches the manifest.
* DRIFT   - a record has the key and differs. It is reported and never written.
* INVALID - the manifest cannot be applied against this server (unknown category, a name that
            another record already uses). Nothing is written when any record is INVALID.

Applying creates only the CREATE records, in dependency order (categories, products, zones,
slots). A 409 or a lost response is reconciled by re-reading: if the record now exists and
matches, the create counts as done; if it exists and differs, the run stops with CONFLICT. After
the first FAILED or CONFLICT the rest are NOT_RUN. Every run starts from a fresh read, so a
rerun after any failure is safe and creates only what is still missing.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from typing import Any

from .client import ApiClient, JsonObject
from .errors import (
    EXIT_AUTH,
    EXIT_DRIFT,
    EXIT_FAILED,
    EXIT_INVALID,
    EXIT_OK,
    ApiError,
    AuthError,
    ProtocolError,
    TransportError,
)
from .manifest import CategorySpec, Manifest, ProductSpec, SlotTemplate, ZoneSpec
from .slots import Expansion, SlotSpec, expand, format_instant, parse_instant

OP_CREATE = "CREATE"
OP_NOOP = "NOOP"
OP_DRIFT = "DRIFT"
OP_INVALID = "INVALID"

OUT_PLANNED = "PLANNED"
OUT_CREATED = "CREATED"
OUT_MATCHING = "EXISTS_MATCHING"
OUT_CONFLICT = "CONFLICT"
OUT_FAILED = "FAILED"
OUT_NOT_RUN = "NOT_RUN"

CODE_AUTH = "AUTH"

Spec = CategorySpec | ProductSpec | ZoneSpec | SlotSpec


@dataclass
class Action:
    """What the report says about one record: its kind, natural key, and outcome."""

    kind: str  # category | product | zone | slot
    key: str  # slug, zone code, or "<start>/<end>" for a slot; never a name or a price
    op: str
    fields: list[str] = field(default_factory=list)  # drifting field names, or a reason code
    outcome: str = OUT_PLANNED
    error_code: str | None = None


@dataclass
class _Item:
    action: Action
    spec: Spec


@dataclass
class Plan:
    items: list[_Item] = field(default_factory=list)
    expansion: Expansion | None = None
    outside_template_slots: int = 0

    @property
    def actions(self) -> list[Action]:
        return [item.action for item in self.items]

    @property
    def invalid(self) -> bool:
        return any(a.op == OP_INVALID for a in self.actions)


def exit_code_for(plan: Plan) -> int:
    actions = plan.actions
    if any(a.error_code == CODE_AUTH for a in actions):
        return EXIT_AUTH
    if any(a.op == OP_INVALID for a in actions):
        return EXIT_INVALID
    if any(a.outcome in (OUT_FAILED, OUT_CONFLICT) for a in actions):
        return EXIT_FAILED
    if any(a.op == OP_DRIFT for a in actions):
        return EXIT_DRIFT
    return EXIT_OK


# ---------------------------------------------------------------------------- comparison


def _money(value: Any) -> Decimal | None:
    if value is None:
        return None
    try:
        return Decimal(str(value)).quantize(Decimal("0.01"))
    except ArithmeticError:
        return None


def _category_drift(spec: CategorySpec, existing: JsonObject) -> list[str]:
    drift: list[str] = []
    if existing.get("name") != spec.name:
        drift.append("name")
    if existing.get("sortOrder") != spec.sort_order:
        drift.append("sortOrder")
    if existing.get("active") != spec.active:
        drift.append("active")
    return drift


def _product_drift(spec: ProductSpec, existing: JsonObject, category_id: str | None) -> list[str]:
    drift: list[str] = []
    if category_id is None or existing.get("categoryId") != category_id:
        drift.append("category")
    if existing.get("name") != spec.name:
        drift.append("name")
    if (existing.get("description") or None) != spec.description:
        drift.append("description")
    if _money(existing.get("basePrice")) != spec.base_price:
        drift.append("basePrice")
    if existing.get("minLeadHours") != spec.min_lead_hours:
        drift.append("minLeadHours")
    if existing.get("productionUnits") != spec.production_units:
        drift.append("productionUnits")
    if existing.get("cancellationCutoffHours") != spec.cancellation_cutoff_hours:
        drift.append("cancellationCutoffHours")
    if existing.get("status") != spec.status:
        drift.append("status")
    if (existing.get("imageUrl") or None) != spec.image_url:
        drift.append("imageUrl")
    return drift


def _zone_drift(spec: ZoneSpec, existing: JsonObject) -> list[str]:
    drift: list[str] = []
    if existing.get("name") != spec.name:
        drift.append("name")
    if _money(existing.get("deliveryFee")) != spec.delivery_fee:
        drift.append("deliveryFee")
    if _money(existing.get("minOrderAmount")) != spec.min_order_amount:
        drift.append("minOrderAmount")
    if existing.get("active") != spec.active:
        drift.append("active")
    if existing.get("sortOrder") != spec.sort_order:
        drift.append("sortOrder")
    return drift


def _slot_drift(spec: SlotSpec, existing: JsonObject) -> list[str]:
    drift: list[str] = []
    if existing.get("capacityUnits") != spec.capacity_units:
        drift.append("capacityUnits")
    if existing.get("active") != spec.active:
        drift.append("active")
    return drift


def _slot_key(existing: JsonObject) -> tuple[datetime, datetime]:
    try:
        return (parse_instant(str(existing["startAt"])), parse_instant(str(existing["endAt"])))
    except (KeyError, ValueError):
        raise ProtocolError("a slot has no readable start and end") from None


def _slot_name(spec: SlotSpec) -> str:
    return f"{format_instant(spec.start_at)}/{format_instant(spec.end_at)}"


# ---------------------------------------------------------------------------- engine


class Engine:
    def __init__(self, client: ApiClient, manifest: Manifest, now: datetime) -> None:
        self._client = client
        self._manifest = manifest
        self._now = now
        self._category_ids: dict[str, str] = {}
        self._slot_range: tuple[date, date] | None = None
        self._halted = False

    # ------------------------------------------------------------------ planning (reads only)

    def plan(self) -> Plan:
        m = self._manifest
        plan = Plan()
        if m.categories or m.products:
            self._plan_categories(plan, self._read(self._client.list_categories))
        if m.products:
            self._plan_products(plan, self._read(self._client.list_products))
        if m.zones:
            self._plan_zones(plan, self._read(self._client.list_zones))
        if m.slot_template is not None:
            self._plan_slots(plan, m.slot_template)
        return plan

    @staticmethod
    def _read(call: Any, *args: Any) -> Any:
        try:
            return call(*args)
        except ApiError as err:
            if err.status in (401, 403):
                raise AuthError(f"the API rejected the credentials ({err.code})") from None
            raise ProtocolError(f"a read failed ({err.code})") from None
        except TransportError:
            raise ProtocolError("a read did not complete") from None

    def _plan_categories(self, plan: Plan, existing: list[JsonObject]) -> None:
        by_slug = {str(c.get("slug")): c for c in existing}
        names = {str(c.get("name")): str(c.get("slug")) for c in existing}
        for slug, record in by_slug.items():
            if isinstance(record.get("id"), str):
                self._category_ids[slug] = str(record["id"])
        for spec in self._manifest.categories:
            current = by_slug.get(spec.slug)
            if current is not None:
                drift = _category_drift(spec, current)
                plan.items.append(_Item(_action("category", spec.slug, drift), spec))
            elif spec.name in names:
                plan.items.append(_Item(_invalid("category", spec.slug, "NAME_TAKEN"), spec))
            else:
                plan.items.append(_Item(Action("category", spec.slug, OP_CREATE), spec))

    def _plan_products(self, plan: Plan, existing: list[JsonObject]) -> None:
        by_slug = {str(p.get("slug")): p for p in existing}
        known = set(self._category_ids) | {c.slug for c in self._manifest.categories}
        for spec in self._manifest.products:
            if spec.category_slug not in known:
                plan.items.append(_Item(_invalid("product", spec.slug, "UNKNOWN_CATEGORY"), spec))
                continue
            current = by_slug.get(spec.slug)
            if current is None:
                plan.items.append(_Item(Action("product", spec.slug, OP_CREATE), spec))
                continue
            drift = _product_drift(spec, current, self._category_ids.get(spec.category_slug))
            plan.items.append(_Item(_action("product", spec.slug, drift), spec))

    def _plan_zones(self, plan: Plan, existing: list[JsonObject]) -> None:
        by_code = {str(z.get("code")): z for z in existing}
        names = {str(z.get("name")) for z in existing}
        for spec in self._manifest.zones:
            current = by_code.get(spec.code)
            if current is not None:
                plan.items.append(_Item(_action("zone", spec.code, _zone_drift(spec, current)), spec))
            elif spec.name in names:
                plan.items.append(_Item(_invalid("zone", spec.code, "NAME_TAKEN"), spec))
            else:
                plan.items.append(_Item(Action("zone", spec.code, OP_CREATE), spec))

    def _plan_slots(self, plan: Plan, template: SlotTemplate) -> None:
        expansion = expand(template, self._now)
        plan.expansion = expansion
        self._slot_range = (expansion.date_from, expansion.date_to)
        existing = self._read(self._client.list_slots, expansion.date_from, expansion.date_to)
        by_key = {_slot_key(s): s for s in existing}
        for spec in expansion.slots:
            current = by_key.get(spec.key)
            if current is None:
                plan.items.append(_Item(Action("slot", _slot_name(spec), OP_CREATE), spec))
            else:
                drift = _slot_drift(spec, current)
                plan.items.append(_Item(_action("slot", _slot_name(spec), drift), spec))
        owned = {s.key for s in expansion.slots} | set(expansion.past_keys)
        plan.outside_template_slots = sum(1 for key in by_key if key not in owned)

    # ------------------------------------------------------------------ applying (writes)

    def apply(self, plan: Plan) -> None:
        """Create every CREATE record. Refuses to run on an invalid plan."""
        if plan.invalid:
            raise ProtocolError("the plan has invalid records; nothing is written")
        for item in plan.items:
            if item.action.op != OP_CREATE:
                continue
            if self._halted:
                item.action.outcome = OUT_NOT_RUN
                continue
            self._create(item)

    def _create(self, item: _Item) -> None:
        payload = self._payload(item)
        if payload is None:
            self._fail(item, "UNKNOWN_CATEGORY")
            return
        try:
            created = self._post(item.spec, payload)
        except ApiError as err:
            if err.status == 409:
                self._reconcile(item, err.code)
            elif err.status in (401, 403):
                self._fail(item, CODE_AUTH)
            else:
                self._fail(item, err.code)
        except TransportError:
            # The write may have reached the server before the answer was lost.
            self._reconcile(item, "TRANSPORT_ERROR")
        except ProtocolError:
            self._fail(item, "PROTOCOL")
        else:
            item.action.outcome = OUT_CREATED
            self._remember(item.spec, created)

    def _post(self, spec: Spec, payload: JsonObject) -> JsonObject:
        if isinstance(spec, CategorySpec):
            return self._client.create_category(payload)
        if isinstance(spec, ProductSpec):
            return self._client.create_product(payload)
        if isinstance(spec, ZoneSpec):
            return self._client.create_zone(payload)
        return self._client.create_slot(payload)

    def _reconcile(self, item: _Item, original_code: str) -> None:
        """Re-read after a 409 or a lost answer, and decide whether the record is as wanted."""
        try:
            existing = self._find(item.spec)
        except ApiError as err:
            self._fail(item, CODE_AUTH if err.status in (401, 403) else "REREAD_FAILED")
            return
        except (TransportError, ProtocolError):
            self._fail(item, "REREAD_FAILED")
            return
        if existing is None:
            self._fail(item, original_code)  # a 409 for some other rule, or nothing was written
            return
        self._remember(item.spec, existing)
        drift = self._drift(item.spec, existing)
        if drift:
            item.action.outcome = OUT_CONFLICT
            item.action.fields = drift
            self._halted = True
        else:
            item.action.outcome = OUT_MATCHING

    def _find(self, spec: Spec) -> JsonObject | None:
        if isinstance(spec, CategorySpec):
            return next((c for c in self._client.list_categories() if c.get("slug") == spec.slug), None)
        if isinstance(spec, ProductSpec):
            return next((p for p in self._client.list_products() if p.get("slug") == spec.slug), None)
        if isinstance(spec, ZoneSpec):
            return next((z for z in self._client.list_zones() if z.get("code") == spec.code), None)
        if self._slot_range is None:
            raise ProtocolError("no slot range")
        listed = self._client.list_slots(*self._slot_range)
        return next((s for s in listed if _slot_key(s) == spec.key), None)

    def _drift(self, spec: Spec, existing: JsonObject) -> list[str]:
        if isinstance(spec, CategorySpec):
            return _category_drift(spec, existing)
        if isinstance(spec, ProductSpec):
            return _product_drift(spec, existing, self._category_ids.get(spec.category_slug))
        if isinstance(spec, ZoneSpec):
            return _zone_drift(spec, existing)
        return _slot_drift(spec, existing)

    def _remember(self, spec: Spec, record: JsonObject) -> None:
        if isinstance(spec, CategorySpec) and isinstance(record.get("id"), str):
            self._category_ids[spec.slug] = str(record["id"])

    def _fail(self, item: _Item, code: str) -> None:
        item.action.outcome = OUT_FAILED
        item.action.error_code = code
        self._halted = True

    def _payload(self, item: _Item) -> JsonObject | None:
        spec = item.spec
        if isinstance(spec, CategorySpec):
            return {
                "name": spec.name,
                "slug": spec.slug,
                "sortOrder": spec.sort_order,
                "active": spec.active,
            }
        if isinstance(spec, ProductSpec):
            category_id = self._category_ids.get(spec.category_slug)
            if category_id is None:
                return None
            body: JsonObject = {
                "categoryId": category_id,
                "name": spec.name,
                "slug": spec.slug,
                "basePrice": spec.base_price,
                "minLeadHours": spec.min_lead_hours,
                "productionUnits": spec.production_units,
                "status": spec.status,
                "cancellationCutoffHours": spec.cancellation_cutoff_hours,
            }
            if spec.description is not None:
                body["description"] = spec.description
            if spec.image_url is not None:
                body["imageUrl"] = spec.image_url
            return body
        if isinstance(spec, ZoneSpec):
            zone: JsonObject = {
                "code": spec.code,
                "name": spec.name,
                "active": spec.active,
                "sortOrder": spec.sort_order,
            }
            if spec.delivery_fee is not None:
                zone["deliveryFee"] = spec.delivery_fee
            if spec.min_order_amount is not None:
                zone["minOrderAmount"] = spec.min_order_amount
            return zone
        return {
            "startAt": format_instant(spec.start_at),
            "endAt": format_instant(spec.end_at),
            "capacityUnits": spec.capacity_units,
            "active": spec.active,
        }


def _action(kind: str, key: str, drift: list[str]) -> Action:
    if drift:
        return Action(kind, key, OP_DRIFT, fields=list(drift))
    return Action(kind, key, OP_NOOP)


def _invalid(kind: str, key: str, reason: str) -> Action:
    return Action(kind, key, OP_INVALID, fields=[reason])
