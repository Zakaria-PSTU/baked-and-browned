"""Delivery-slot expansion: a weekly template becomes concrete slots over a rolling horizon.

Time zone. The business calendar is Asia/Dhaka (docs/business-rules.md; ``BusinessTime.ZONE`` in
the backend). Dhaka is UTC+06:00 all year: the JDK 25 rules for the zone (checked 2026-11-23) show no
transition after 2010 and none scheduled, so a fixed offset is exact today. It is used instead of ``zoneinfo``
because the standard library has no zone database on Windows without the ``tzdata`` package, and
this tool takes no third-party dependency. If Bangladesh ever reintroduces daylight saving time,
this module must change; the README says so.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

from .manifest import SlotTemplate

DHAKA = timezone(timedelta(hours=6), "Asia/Dhaka")

# A slot whose start is not safely in the future is skipped, not sent: the server rejects a
# start that is not after its own clock, and the two clocks may differ slightly.
START_MARGIN = timedelta(seconds=60)


@dataclass(frozen=True)
class SlotSpec:
    start_at: datetime  # UTC
    end_at: datetime  # UTC
    capacity_units: int
    active: bool

    @property
    def key(self) -> tuple[datetime, datetime]:
        return (self.start_at, self.end_at)


@dataclass(frozen=True)
class Expansion:
    slots: tuple[SlotSpec, ...]
    skipped_past: int
    past_keys: frozenset[tuple[datetime, datetime]]  # template windows that already started
    date_from: date  # first Dhaka date of the horizon, inclusive
    date_to: date  # last Dhaka date of the horizon, inclusive


def format_instant(moment: datetime) -> str:
    """The wire format of an instant: UTC, whole seconds, a trailing Z."""
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_instant(text: str) -> datetime:
    """Parse an ISO-8601 instant from the API into an aware UTC datetime."""
    moment = datetime.fromisoformat(text)
    if moment.tzinfo is None:
        raise ValueError("instant has no offset")
    return moment.astimezone(timezone.utc)


def expand(template: SlotTemplate, now: datetime) -> Expansion:
    """Concrete slots for ``template.horizon_days`` Dhaka dates, starting with today (Dhaka)."""
    if now.tzinfo is None:
        raise ValueError("now must be timezone-aware")
    today = now.astimezone(DHAKA).date()
    last = today + timedelta(days=template.horizon_days - 1)
    slots: list[SlotSpec] = []
    past: set[tuple[datetime, datetime]] = set()
    for offset in range(template.horizon_days):
        day = today + timedelta(days=offset)
        for rule in template.rules:
            if day.weekday() not in rule.weekdays:
                continue
            for window in rule.windows:
                start = datetime.combine(day, window.start, tzinfo=DHAKA)
                end = datetime.combine(day, window.end, tzinfo=DHAKA)
                spec = SlotSpec(
                    start.astimezone(timezone.utc),
                    end.astimezone(timezone.utc),
                    window.capacity_units,
                    window.active,
                )
                if start <= now + START_MARGIN:
                    past.add(spec.key)
                    continue
                slots.append(spec)
    slots.sort(key=lambda s: s.key)
    return Expansion(tuple(slots), len(past), frozenset(past), today, last)
