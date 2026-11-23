"""Slot expansion: Asia/Dhaka (UTC+06:00, no daylight saving), date boundaries, past slots."""

from __future__ import annotations

import json
import unittest
from datetime import datetime, timedelta, timezone

from catalogue_loader.manifest import SlotTemplate, parse_manifest
from catalogue_loader.slots import DHAKA, START_MARGIN, expand, format_instant, parse_instant

from .fixtures import NOW, sample_manifest


def template(**changes: object) -> SlotTemplate:
    data = sample_manifest()
    data["slotTemplate"].update(changes)
    manifest = parse_manifest(json.dumps({"version": 1, "slotTemplate": data["slotTemplate"]}))
    assert manifest.slot_template is not None
    return manifest.slot_template


class SlotExpansionTest(unittest.TestCase):
    def test_a_fourteen_day_horizon_from_a_monday(self) -> None:
        result = expand(template(), NOW)  # Monday 09:00 Dhaka
        self.assertEqual((0, 24), (result.skipped_past, len(result.slots)))
        self.assertEqual("2026-11-23", result.date_from.isoformat())
        self.assertEqual("2026-12-06", result.date_to.isoformat())  # 14 days, inclusive
        weekdays = {s.start_at.astimezone(DHAKA).weekday() for s in result.slots}
        self.assertEqual({0, 1, 2, 3, 4, 5}, weekdays)  # no Sunday in the rule

    def test_times_are_dhaka_local_and_sent_as_utc(self) -> None:
        first = expand(template(), NOW).slots[0]
        self.assertEqual("2026-11-23T04:00:00Z", format_instant(first.start_at))  # 10:00 +06:00
        self.assertEqual("2026-11-23T06:00:00Z", format_instant(first.end_at))
        self.assertEqual((10, 8), (first.capacity_units, expand(template(), NOW).slots[1].capacity_units))

    def test_windows_that_already_started_are_skipped_not_sent(self) -> None:
        now = datetime(2026, 11, 23, 5, 0, tzinfo=timezone.utc)  # 11:00 Dhaka: 10:00 slot has started
        result = expand(template(), now)
        self.assertEqual(1, result.skipped_past)
        self.assertEqual(23, len(result.slots))
        self.assertTrue(all(s.start_at > now for s in result.slots))
        self.assertEqual(1, len(result.past_keys))

    def test_the_start_margin(self) -> None:
        # Hard-coded on purpose: the server rejects a start that is not after its own clock, and
        # the 60-second margin is what absorbs a small clock difference between us and it.
        start = datetime(2026, 11, 23, 4, 0, tzinfo=timezone.utc)  # the 10:00 Dhaka window
        self.assertEqual(timedelta(seconds=60), START_MARGIN)
        self.assertEqual(1, expand(template(), start - timedelta(seconds=59)).skipped_past)  # too close
        self.assertEqual(1, expand(template(), start).skipped_past)  # starting right now
        self.assertEqual(0, expand(template(), start - timedelta(seconds=61)).skipped_past)  # safe

    def test_the_dhaka_date_not_the_utc_date_defines_today(self) -> None:
        # 20:00 UTC on Sunday 22 Nov is already 02:00 on Monday 23 Nov in Dhaka.
        now = datetime(2026, 11, 22, 20, 0, tzinfo=timezone.utc)
        result = expand(template(), now)
        self.assertEqual("2026-11-23", result.date_from.isoformat())
        self.assertEqual(24, len(result.slots))

    def test_the_horizon_rolls_over_at_dhaka_midnight(self) -> None:
        before = datetime(2026, 11, 23, 17, 59, 30, tzinfo=timezone.utc)  # 23:59:30 Dhaka, Monday
        after = before + timedelta(seconds=45)  # 00:00:15 Dhaka, Tuesday
        self.assertEqual("2026-11-23", expand(template(), before).date_from.isoformat())
        self.assertEqual("2026-11-24", expand(template(), after).date_from.isoformat())

    def test_a_window_just_after_midnight_lands_on_the_previous_utc_day(self) -> None:
        data = sample_manifest()["slotTemplate"]
        data["rules"] = [{"weekdays": ["tue"], "windows": [{"start": "00:30", "end": "01:30", "capacityUnits": 3}]}]
        tpl = parse_manifest(json.dumps({"version": 1, "slotTemplate": data})).slot_template
        assert tpl is not None
        slot = expand(tpl, NOW).slots[0]
        self.assertEqual("2026-11-23T18:30:00Z", format_instant(slot.start_at))  # Tuesday 00:30 Dhaka
        self.assertEqual(1, slot.start_at.astimezone(DHAKA).weekday())

    def test_dhaka_has_no_daylight_saving_offset_all_year(self) -> None:
        for month in range(1, 13):
            moment = datetime(2026, month, 15, 12, 0, tzinfo=DHAKA)
            self.assertEqual(timedelta(hours=6), moment.utcoffset(), f"month {month}")

    def test_parse_instant_accepts_the_apis_forms(self) -> None:
        expected = datetime(2026, 11, 23, 4, 0, tzinfo=timezone.utc)
        for text in ("2026-11-23T04:00:00Z", "2026-11-23T10:00:00+06:00", "2026-11-23T04:00:00.000Z"):
            self.assertEqual(expected, parse_instant(text), text)
        with self.assertRaises(ValueError):
            parse_instant("2026-11-23T04:00:00")  # no offset

    def test_horizon_length_is_honoured(self) -> None:
        one_day = expand(template(horizonDays=1), NOW)
        self.assertEqual("2026-11-23", one_day.date_to.isoformat())
        self.assertEqual(2, len(one_day.slots))  # Monday's two windows only

    def test_naive_now_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            expand(template(), datetime(2026, 11, 23, 3, 0))


if __name__ == "__main__":
    unittest.main()
