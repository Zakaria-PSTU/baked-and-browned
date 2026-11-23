"""Idempotency, drift, conflicts and partial failure, against the in-memory fake API."""

from __future__ import annotations

import copy
import json
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

from catalogue_loader.manifest import parse_manifest
from catalogue_loader.slots import expand

from .fakes import FakeApi
from .fixtures import NOW, LoaderTestCase, sample_manifest

ADMIN = "/api/v1/admin"
CATEGORIES, PRODUCTS = f"{ADMIN}/categories", f"{ADMIN}/products"
ZONES, SLOTS = f"{ADMIN}/delivery-zones", f"{ADMIN}/delivery-slots"


def template_slots() -> Any:
    manifest = parse_manifest(json.dumps(sample_manifest()))
    assert manifest.slot_template is not None
    return expand(manifest.slot_template, NOW).slots


def seed_matching(fake: FakeApi) -> str:
    """Records identical to sample_manifest(). Returns the category id."""
    category_id = fake.add_category("example-cakes", "Example Cakes", 10, True)
    fake.add_product(
        "example-fudge-cake", "Example Fudge Cake", category_id, "123.45",
        description="A fictional cake.", productionUnits=2,
    )
    fake.add_zone("EXAMPLE_NORTH", "Example North", "11.00", True, minimum="50.00", sort_order=10)
    fake.add_zone("EXAMPLE_SOUTH", "Example South", None, False)
    for slot in template_slots():
        fake.add_slot(slot.start_at, slot.end_at, slot.capacity_units, slot.active)
    return category_id


class DryRunTest(LoaderTestCase):
    def test_a_dry_run_on_an_empty_server_plans_every_create_and_writes_nothing(self) -> None:
        result = self.run_cli()
        self.assertEqual(0, result.code)
        self.assertEqual({"CREATE/PLANNED": 1}, result.ops("category"))
        self.assertEqual({"CREATE/PLANNED": 1}, result.ops("product"))
        self.assertEqual({"CREATE/PLANNED": 2}, result.ops("zone"))
        self.assertEqual({"CREATE/PLANNED": 24}, result.ops("slot"))
        self.assertEqual([], self.fake.writes)
        self.assertEqual({"GET"}, {method for method, _ in self.fake.calls})
        self.assertEqual((0, 0, 0, 0), (len(self.fake.categories), len(self.fake.products),
                                         len(self.fake.zones), len(self.fake.slots)))

    def test_a_dry_run_is_the_default_and_reports_its_mode(self) -> None:
        result = self.run_cli()
        assert result.report is not None
        self.assertEqual("dry-run", result.report["mode"])

    def test_a_dry_run_with_drift_still_writes_nothing(self) -> None:
        category_id = seed_matching(self.fake)
        self.fake.categories[category_id]["name"] = "Renamed By Hand"
        result = self.run_cli()
        self.assertEqual(4, result.code)
        self.assertEqual([], self.fake.writes)


class ApplyAndIdempotencyTest(LoaderTestCase):
    def test_apply_creates_every_record_in_dependency_order(self) -> None:
        result = self.run_cli(apply=True)
        self.assertEqual(0, result.code)
        self.assertEqual((1, 1, 2, 24), (len(self.fake.categories), len(self.fake.products),
                                         len(self.fake.zones), len(self.fake.slots)))
        paths = [path for _, path in self.fake.writes]
        self.assertEqual([CATEGORIES, PRODUCTS, ZONES, ZONES] + [SLOTS] * 24, paths)

    def test_created_records_carry_the_manifest_values(self) -> None:
        self.run_cli(apply=True)
        category = next(iter(self.fake.categories.values()))
        product = next(iter(self.fake.products.values()))
        self.assertEqual(category["id"], product["categoryId"])  # the new category's id was used
        self.assertEqual((Decimal("123.45"), "DRAFT", 24), (product["basePrice"], product["status"],
                                                          product["cancellationCutoffHours"]))
        zones = {z["code"]: z for z in self.fake.zones.values()}
        self.assertEqual(Decimal("11.00"), zones["EXAMPLE_NORTH"]["deliveryFee"])
        self.assertIsNone(zones["EXAMPLE_SOUTH"]["deliveryFee"])
        starts = sorted(s["startAt"] for s in self.fake.slots.values())
        self.assertEqual("2026-11-23T04:00:00Z", starts[0])

    def test_a_second_apply_is_all_noop_and_sends_no_write(self) -> None:
        self.run_cli(apply=True)
        writes_after_first = len(self.fake.writes)
        state = copy.deepcopy((self.fake.categories, self.fake.products, self.fake.zones, self.fake.slots))
        second = self.run_cli(apply=True)
        self.assertEqual(0, second.code)
        self.assertEqual(writes_after_first, len(self.fake.writes))
        self.assertEqual(state, (self.fake.categories, self.fake.products, self.fake.zones, self.fake.slots))
        self.assertEqual({"NOOP/PLANNED": 24}, second.ops("slot"))
        self.assertEqual({"NOOP/PLANNED": 1}, second.ops("product"))

    def test_many_repeated_runs_converge_to_one_state(self) -> None:
        for _ in range(4):
            self.assertEqual(0, self.run_cli(apply=True).code)
        self.assertEqual((1, 1, 2, 24), (len(self.fake.categories), len(self.fake.products),
                                         len(self.fake.zones), len(self.fake.slots)))
        self.assertEqual(1 + 1 + 2 + 24, len(self.fake.writes))

    def test_existing_matching_records_are_left_alone(self) -> None:
        seed_matching(self.fake)
        result = self.run_cli(apply=True)
        self.assertEqual(0, result.code)
        self.assertEqual([], self.fake.writes)
        for kind in ("category", "product", "zone", "slot"):
            self.assertTrue(all(label.startswith("NOOP") for label in result.ops(kind)), kind)

    def test_only_the_missing_records_are_created(self) -> None:
        seed_matching(self.fake)
        first_slot = next(iter(self.fake.slots))
        del self.fake.slots[first_slot]
        del self.fake.zones[next(z for z, r in self.fake.zones.items() if r["code"] == "EXAMPLE_SOUTH")]
        result = self.run_cli(apply=True)
        self.assertEqual(0, result.code)
        self.assertEqual([ZONES, SLOTS], [path for _, path in self.fake.writes])
        self.assertEqual((24, 2), (len(self.fake.slots), len(self.fake.zones)))
        self.assertEqual({"CREATE/CREATED": 1, "NOOP/PLANNED": 23}, result.ops("slot"))

    def test_products_are_found_across_every_page(self) -> None:
        category_id = self.fake.add_category("example-cakes", "Example Cakes", 10, True)
        for i in range(250):
            self.fake.add_product(f"bulk-product-{i}", f"Bulk Product {i}", category_id, "10.00")
        self.fake.add_product("example-fudge-cake", "Example Fudge Cake", category_id, "123.45",
                              description="A fictional cake.", productionUnits=2)
        manifest = sample_manifest()
        for section in ("zones", "slotTemplate"):
            del manifest[section]
        result = self.run_cli(manifest, apply=True)
        self.assertEqual(0, result.code)
        self.assertEqual({"NOOP/PLANNED": 1}, result.ops("product"))  # found past page 2, not re-created
        self.assertEqual([], self.fake.writes)
        product_reads = [c for c in self.fake.calls if c == ("GET", PRODUCTS)]
        self.assertEqual(3, len(product_reads))  # 251 products at 100 per page


class DriftSafetyTest(LoaderTestCase):
    """Existing records that differ are reported and never overwritten (no update in v1)."""

    def test_drift_is_reported_and_never_written(self) -> None:
        category_id = self.fake.add_category("example-cakes", "Different Name", 10, True)
        self.fake.add_product("example-fudge-cake", "Example Fudge Cake", category_id, "999.00",
                              description="A fictional cake.", productionUnits=2)
        self.fake.add_zone("EXAMPLE_NORTH", "Example North", "99.00", True, minimum="50.00", sort_order=10)
        first = template_slots()[0]
        self.fake.add_slot(first.start_at, first.end_at, 99, False)
        before_categories = copy.deepcopy(self.fake.categories)
        before_products = copy.deepcopy(self.fake.products)
        before_north = copy.deepcopy(next(iter(self.fake.zones.values())))
        before_slot = copy.deepcopy(next(iter(self.fake.slots.values())))

        result = self.run_cli(apply=True)

        self.assertEqual(4, result.code)
        self.assertEqual("DRIFT", (result.report or {})["result"])
        drift = {a["key"]: a["fields"] for a in (result.report or {})["actions"] if a["op"] == "DRIFT"}
        self.assertEqual(["name"], drift["example-cakes"])
        self.assertEqual(["basePrice"], drift["example-fudge-cake"])
        self.assertEqual(["deliveryFee"], drift["EXAMPLE_NORTH"])
        self.assertEqual(["capacityUnits", "active"], next(v for k, v in drift.items() if "/" in k))
        # The drifting records are exactly what they were; only missing records were created.
        self.assertEqual(before_categories, self.fake.categories)
        self.assertEqual(before_products, self.fake.products)
        self.assertEqual(before_north, self.fake.zones[before_north["id"]])
        self.assertEqual(before_slot, self.fake.slots[before_slot["id"]])
        self.assertEqual(Decimal("999.00"), next(iter(self.fake.products.values()))["basePrice"])
        # Creating the missing records still worked: the second zone and the other 23 slots.
        self.assertEqual(24, len(self.fake.slots))
        self.assertEqual(2, len(self.fake.zones))

    def test_a_closed_slot_is_not_reopened(self) -> None:
        seed_matching(self.fake)
        slot_id = next(iter(self.fake.slots))
        self.fake.slots[slot_id]["active"] = False  # an operator closed it by hand
        result = self.run_cli(apply=True)
        self.assertEqual(4, result.code)
        self.assertFalse(self.fake.slots[slot_id]["active"])
        self.assertEqual([], self.fake.writes)

    def test_a_deactivated_zone_stays_deactivated(self) -> None:
        seed_matching(self.fake)
        zone = next(z for z in self.fake.zones.values() if z["code"] == "EXAMPLE_NORTH")
        zone["active"] = False
        self.assertEqual(4, self.run_cli(apply=True).code)
        self.assertFalse(zone["active"])

    def test_a_product_in_another_category_is_drift_not_a_move(self) -> None:
        seed_matching(self.fake)
        other = self.fake.add_category("other-cakes", "Other Cakes")
        product = next(iter(self.fake.products.values()))
        product["categoryId"] = other
        result = self.run_cli(apply=True)
        self.assertEqual(4, result.code)
        self.assertEqual(other, product["categoryId"])
        drift = [a for a in (result.report or {})["actions"] if a["op"] == "DRIFT"]
        self.assertEqual(["category"], drift[0]["fields"])

    def test_slots_outside_the_template_are_neither_touched_nor_counted_as_drift(self) -> None:
        seed_matching(self.fake)
        sunday = datetime(2026, 11, 29, 4, 0, tzinfo=timezone.utc)
        self.fake.add_slot(sunday, sunday.replace(hour=6), 5)
        result = self.run_cli(apply=True)
        self.assertEqual(0, result.code)
        self.assertEqual(25, len(self.fake.slots))
        self.assertEqual(1, (result.report or {})["slotWindow"]["existingOutsideTemplate"])

    def test_the_loader_never_issues_an_update_or_delete(self) -> None:
        seed_matching(self.fake)
        next(iter(self.fake.zones.values()))["name"] = "Changed By Hand"
        self.run_cli(apply=True)
        self.assertEqual({"GET"}, {method for method, _ in self.fake.calls})  # tearDown checks all tests too


class InvalidPlanTest(LoaderTestCase):
    def test_a_name_used_by_another_key_is_invalid_and_nothing_is_written(self) -> None:
        self.fake.add_category("other-cakes", "Example Cakes")  # the name, under a different slug
        result = self.run_cli(apply=True)
        self.assertEqual(1, result.code)
        self.assertEqual([], self.fake.writes)
        invalid = [a for a in (result.report or {})["actions"] if a["op"] == "INVALID"]
        self.assertEqual(["NAME_TAKEN"], invalid[0]["fields"])
        self.assertNotIn("CREATED", json.dumps(result.report))  # nothing was attempted

    def test_a_zone_name_used_by_another_code_is_invalid(self) -> None:
        self.fake.add_zone("OTHER_CODE", "Example North", "5.00", True)
        result = self.run_cli(apply=True)
        self.assertEqual(1, result.code)
        self.assertEqual([], self.fake.writes)

    def test_a_product_in_an_unknown_category_is_invalid(self) -> None:
        manifest = sample_manifest()
        manifest["products"][0]["category"] = "no-such-category"
        result = self.run_cli(manifest, apply=True)
        self.assertEqual(1, result.code)
        self.assertEqual([], self.fake.writes)
        self.assertEqual(0, len(self.fake.categories))  # the valid category was not created either

    def test_a_product_may_use_a_category_that_already_exists_on_the_server(self) -> None:
        self.fake.add_category("server-only", "Server Only")
        manifest = sample_manifest()
        manifest["products"][0]["category"] = "server-only"
        del manifest["categories"]
        result = self.run_cli(manifest, apply=True)
        self.assertEqual(0, result.code)
        self.assertEqual(1, len(self.fake.products))


class ConflictAndConcurrencyTest(LoaderTestCase):
    def test_a_409_where_the_record_now_matches_is_treated_as_done(self) -> None:
        def concurrent_writer() -> None:
            self.fake.add_category("example-cakes", "Example Cakes", 10, True)

        self.fake.before_post(CATEGORIES, concurrent_writer)
        result = self.run_cli(apply=True)
        self.assertEqual(0, result.code)
        self.assertEqual({"CREATE/EXISTS_MATCHING": 1}, result.ops("category"))
        self.assertEqual(1, len(self.fake.categories))
        product = next(iter(self.fake.products.values()))  # created against the other writer's category id
        self.assertEqual(next(iter(self.fake.categories)), product["categoryId"])

    def test_a_409_where_the_record_now_differs_stops_the_run(self) -> None:
        def concurrent_writer() -> None:
            self.fake.add_category("example-cakes", "Someone Else's Name", 10, True)

        self.fake.before_post(CATEGORIES, concurrent_writer)
        result = self.run_cli(apply=True)
        self.assertEqual(2, result.code)
        self.assertEqual({"CREATE/CONFLICT": 1}, result.ops("category"))
        self.assertEqual({"CREATE/NOT_RUN": 1}, result.ops("product"))
        self.assertEqual({"CREATE/NOT_RUN": 2}, result.ops("zone"))
        self.assertEqual({"CREATE/NOT_RUN": 24}, result.ops("slot"))
        self.assertEqual((0, 0, 0), (len(self.fake.products), len(self.fake.zones), len(self.fake.slots)))
        self.assertEqual("Someone Else's Name", next(iter(self.fake.categories.values()))["name"])  # untouched

    def test_a_concurrent_slot_with_other_capacity_is_a_conflict(self) -> None:
        first = template_slots()[0]
        manifest = sample_manifest()
        for section in ("categories", "products", "zones"):
            del manifest[section]
        self.fake.before_post(SLOTS, lambda: self.fake.add_slot(first.start_at, first.end_at, 99))
        result = self.run_cli(manifest, apply=True)
        self.assertEqual(2, result.code)
        self.assertEqual({"CREATE/CONFLICT": 1, "CREATE/NOT_RUN": 23}, result.ops("slot"))

    def test_a_409_for_some_other_rule_is_a_failure_not_a_match(self) -> None:
        # The server says 409 but no record with our key exists (for example a name clash that
        # appeared after the plan). It must not be reported as success.
        self.fake.fail("POST", CATEGORIES, 409, "CATEGORY_EXISTS")
        result = self.run_cli(apply=True)
        self.assertEqual(2, result.code)
        self.assertEqual({"CREATE/FAILED": 1}, result.ops("category"))
        action = next(a for a in (result.report or {})["actions"] if a["kind"] == "category")
        self.assertEqual("CATEGORY_EXISTS", action["errorCode"])

    def test_a_lost_answer_after_the_write_committed_is_reconciled(self) -> None:
        self.fake.lose_answer_after_commit(ZONES)
        result = self.run_cli(apply=True)
        self.assertEqual(0, result.code)
        self.assertEqual({"CREATE/EXISTS_MATCHING": 1, "CREATE/CREATED": 1}, result.ops("zone"))
        self.assertEqual(2, len(self.fake.zones))  # one copy each, no duplicate

    def test_a_lost_request_is_a_failure_and_a_rerun_recovers(self) -> None:
        self.fake.lose_request_before_commit(PRODUCTS)
        first = self.run_cli(apply=True)
        self.assertEqual(2, first.code)
        self.assertEqual({"CREATE/FAILED": 1}, first.ops("product"))
        action = next(a for a in (first.report or {})["actions"] if a["kind"] == "product")
        self.assertEqual("TRANSPORT_ERROR", action["errorCode"])
        self.assertEqual((1, 0, 0, 0), (len(self.fake.categories), len(self.fake.products),
                                         len(self.fake.zones), len(self.fake.slots)))
        second = self.run_cli(apply=True)
        self.assertEqual(0, second.code)
        self.assertEqual({"NOOP/PLANNED": 1}, second.ops("category"))
        self.assertEqual((1, 1, 2, 24), (len(self.fake.categories), len(self.fake.products),
                                         len(self.fake.zones), len(self.fake.slots)))


class PartialFailureTest(LoaderTestCase):
    def test_the_run_stops_at_the_first_failure_and_a_rerun_finishes_the_job(self) -> None:
        self.fake.fail("POST", ZONES, 500, "INTERNAL_ERROR")
        first = self.run_cli(apply=True)
        self.assertEqual(2, first.code)
        self.assertEqual("FAILED", (first.report or {})["result"])
        self.assertEqual({"CREATE/CREATED": 1}, first.ops("category"))
        self.assertEqual({"CREATE/CREATED": 1}, first.ops("product"))
        self.assertEqual({"CREATE/FAILED": 1, "CREATE/NOT_RUN": 1}, first.ops("zone"))
        self.assertEqual({"CREATE/NOT_RUN": 24}, first.ops("slot"))
        self.assertEqual((1, 1, 0, 0), (len(self.fake.categories), len(self.fake.products),
                                         len(self.fake.zones), len(self.fake.slots)))

        self.fake.clear_faults()
        second = self.run_cli(apply=True)
        self.assertEqual(0, second.code)
        self.assertEqual({"NOOP/PLANNED": 1}, second.ops("category"))
        self.assertEqual({"NOOP/PLANNED": 1}, second.ops("product"))
        self.assertEqual({"CREATE/CREATED": 2}, second.ops("zone"))
        self.assertEqual({"CREATE/CREATED": 24}, second.ops("slot"))
        self.assertEqual((1, 1, 2, 24), (len(self.fake.categories), len(self.fake.products),
                                         len(self.fake.zones), len(self.fake.slots)))

    def test_the_recovered_state_equals_a_clean_run(self) -> None:
        clean = FakeApi(NOW)
        self.run_cli(apply=True, transport=clean.transport)
        self.fake.fail("POST", SLOTS, 503, "UNAVAILABLE", times=1, skip=5)  # fails on the sixth slot
        self.assertEqual(2, self.run_cli(apply=True).code)
        self.assertEqual(5, len(self.fake.slots))
        self.fake.clear_faults()
        self.assertEqual(0, self.run_cli(apply=True).code)

        def shape(fake: FakeApi) -> Any:
            return (sorted((s["startAt"], s["endAt"], s["capacityUnits"]) for s in fake.slots.values()),
                    sorted(z["code"] for z in fake.zones.values()), len(fake.products), len(fake.categories))

        self.assertEqual(shape(clean), shape(self.fake))

    def test_a_failure_report_names_the_error_code_only(self) -> None:
        self.fake.fail("POST", PRODUCTS, 422, "UNKNOWN_CATEGORY")
        result = self.run_cli(apply=True)
        action = next(a for a in (result.report or {})["actions"] if a["kind"] == "product")
        self.assertEqual(("FAILED", "UNKNOWN_CATEGORY"), (action["outcome"], action["errorCode"]))


class SlotTimeTest(LoaderTestCase):
    def test_slots_that_have_already_started_are_skipped_and_never_posted(self) -> None:
        now = datetime(2026, 11, 23, 5, 0, tzinfo=timezone.utc)  # 11:00 Dhaka: today's 10:00 slot started
        self.fake.now = now
        result = self.run_cli(apply=True, now=now)
        self.assertEqual(0, result.code)
        slot_posts = [c for c in self.fake.writes if c[1] == SLOTS]
        self.assertEqual(23, len(slot_posts))
        self.assertEqual(1, (result.report or {})["slotWindow"]["skippedStarted"])
        self.assertTrue(all(s["startAt"] > "2026-11-23T05:00:00Z" for s in self.fake.slots.values()))

    def test_a_server_clock_ahead_of_ours_is_reported_clearly(self) -> None:
        self.fake.now = datetime(2026, 11, 23, 6, 0, tzinfo=timezone.utc)  # three hours ahead
        result = self.run_cli(apply=True)
        self.assertEqual(2, result.code)
        action = next(a for a in (result.report or {})["actions"] if a["outcome"] == "FAILED")
        self.assertEqual(("slot", "SLOT_IN_PAST"), (action["kind"], action["errorCode"]))
        self.assertEqual(0, len(self.fake.slots))

    def test_the_slot_window_is_reported(self) -> None:
        window = (self.run_cli().report or {})["slotWindow"]
        self.assertEqual(("2026-11-23", "2026-12-06"), (window["from"], window["to"]))
