"""Manifest validation: every problem is found before any network call, and no value is echoed."""

from __future__ import annotations

import copy
import json
import unittest
from typing import Any

from catalogue_loader.errors import ManifestError
from catalogue_loader.manifest import parse_manifest

from .fixtures import sample_manifest


def problems_for(data: Any) -> list[str]:
    try:
        parse_manifest(json.dumps(data))
    except ManifestError as err:
        return err.problems
    return []


def mutate(path: list[Any], value: Any) -> dict[str, Any]:
    data = copy.deepcopy(sample_manifest())
    node: Any = data
    for step in path[:-1]:
        node = node[step]
    node[path[-1]] = value
    return data


class ManifestValidationTest(unittest.TestCase):
    def test_a_valid_manifest_parses(self) -> None:
        manifest = parse_manifest(json.dumps(sample_manifest()))
        self.assertEqual(1, len(manifest.categories))
        self.assertEqual("123.45", str(manifest.products[0].base_price))
        self.assertEqual(2, len(manifest.zones))
        assert manifest.slot_template is not None
        self.assertEqual(14, manifest.slot_template.horizon_days)

    def test_defaults_are_the_apis_defaults(self) -> None:
        data = {"version": 1, "categories": [{"slug": "a-b", "name": "Ab"}]}
        category = parse_manifest(json.dumps(data)).categories[0]
        self.assertEqual((0, True), (category.sort_order, category.active))
        product = copy.deepcopy(sample_manifest())["products"][0]
        for key in ("status", "description"):
            product.pop(key, None)
        parsed = parse_manifest(json.dumps({"version": 1, "products": [product]})).products[0]
        self.assertEqual(("DRAFT", 24, None), (parsed.status, parsed.cancellation_cutoff_hours, parsed.description))

    def test_not_json_and_wrong_root(self) -> None:
        with self.assertRaises(ManifestError):
            parse_manifest("{not json")
        self.assertTrue(problems_for([]))
        self.assertTrue(problems_for({"version": 2, "zones": []}))
        self.assertTrue(problems_for({"categories": []}))  # version is required

    def test_an_empty_manifest_is_rejected(self) -> None:
        self.assertTrue(any("at least one" in p for p in problems_for({"version": 1})))

    def test_unknown_fields_are_rejected(self) -> None:
        data = mutate(["products", 0, "discount"], "10")
        self.assertTrue(any("unknown field" in p for p in problems_for(data)))
        self.assertTrue(problems_for({**sample_manifest(), "extra": 1}))

    def test_duplicate_json_keys_are_rejected(self) -> None:
        text = '{"version": 1, "version": 1, "zones": []}'
        with self.assertRaises(ManifestError) as caught:
            parse_manifest(text)
        self.assertTrue(any("duplicate JSON key" in p for p in caught.exception.problems))

    def test_duplicate_natural_keys_are_rejected(self) -> None:
        data = sample_manifest()
        data["categories"].append(dict(data["categories"][0], name="Another Name"))
        data["products"].append(dict(data["products"][0], name="Another Product"))
        data["zones"].append(dict(data["zones"][0], name="Another Zone"))
        found = problems_for(data)
        self.assertTrue(any("categories[1].slug" in p for p in found))
        self.assertTrue(any("products[1].slug" in p for p in found))
        self.assertTrue(any("zones[2].code" in p for p in found))

    def test_duplicate_names_are_rejected_because_the_server_would_conflict(self) -> None:
        data = sample_manifest()
        data["categories"].append({"slug": "other-slug", "name": "Example Cakes"})
        data["zones"].append({"code": "OTHER_CODE", "name": "Example North", "active": False})
        found = problems_for(data)
        self.assertTrue(any("categories[1].name" in p for p in found))
        self.assertTrue(any("zones[2].name" in p for p in found))

    def test_money_must_be_an_exact_string(self) -> None:
        for bad in (123.45, "12.345", "-1.00", "1,000.00", "abc", "", "12345678901.00", " 1.00"):
            with self.subTest(bad=bad):
                self.assertTrue(problems_for(mutate(["products", 0, "basePrice"], bad)))
        for good in ("0", "0.5", "1200.00", "9999999999.99"):
            with self.subTest(good=good):
                self.assertEqual([], problems_for(mutate(["products", 0, "basePrice"], good)))

    def test_slug_and_code_patterns(self) -> None:
        self.assertTrue(problems_for(mutate(["categories", 0, "slug"], "Bad Slug")))
        self.assertTrue(problems_for(mutate(["products", 0, "slug"], "trailing-")))
        self.assertTrue(problems_for(mutate(["zones", 0, "code"], "lower_case")))
        self.assertTrue(problems_for(mutate(["zones", 0, "code"], "X")))

    def test_numbers_are_checked_and_booleans_are_not_integers(self) -> None:
        self.assertTrue(problems_for(mutate(["products", 0, "minLeadHours"], -1)))
        self.assertTrue(problems_for(mutate(["products", 0, "productionUnits"], 0)))
        self.assertTrue(problems_for(mutate(["products", 0, "productionUnits"], True)))
        self.assertTrue(problems_for(mutate(["products", 0, "cancellationCutoffHours"], 721)))
        self.assertTrue(problems_for(mutate(["products", 0, "minLeadHours"], 1.5)))

    def test_status_and_image_url(self) -> None:
        self.assertTrue(problems_for(mutate(["products", 0, "status"], "PUBLISHED")))
        self.assertEqual([], problems_for(mutate(["products", 0, "status"], "ACTIVE")))
        self.assertTrue(problems_for(mutate(["products", 0, "imageUrl"], "http://images.example.test/a.png")))
        self.assertEqual([], problems_for(mutate(["products", 0, "imageUrl"], "https://images.example.test/a.png")))

    def test_an_active_zone_needs_a_fee(self) -> None:
        data = mutate(["zones", 1, "active"], True)
        self.assertTrue(any("deliveryFee" in p for p in problems_for(data)))
        data = mutate(["zones", 0, "deliveryFee"], None)
        self.assertTrue(any("deliveryFee" in p for p in problems_for(data)))

    def test_template_weekdays_and_windows(self) -> None:
        rules = ["slotTemplate", "rules", 0]
        self.assertTrue(problems_for(mutate(rules + ["weekdays"], ["monday"])))
        self.assertTrue(problems_for(mutate(rules + ["weekdays"], ["mon", "mon"])))
        self.assertTrue(problems_for(mutate(rules + ["weekdays"], [])))
        self.assertTrue(problems_for(mutate(rules + ["windows"], [])))
        self.assertTrue(problems_for(mutate(rules + ["windows", 0, "start"], "24:00")))
        self.assertTrue(problems_for(mutate(rules + ["windows", 0, "start"], "9:00")))
        self.assertTrue(problems_for(mutate(rules + ["windows", 0, "end"], "10:00")))  # end == start
        self.assertTrue(problems_for(mutate(rules + ["windows", 0, "end"], "09:00")))  # end < start
        self.assertTrue(problems_for(mutate(rules + ["windows", 0, "capacityUnits"], 0)))

    def test_a_window_may_end_at_2359_but_not_cross_midnight(self) -> None:
        window = ["slotTemplate", "rules", 0, "windows", 0]
        self.assertEqual([], problems_for(mutate(window + ["end"], "23:59")))
        # 23:00 to 12:00 would have to cross midnight, which a same-day window cannot do.
        self.assertTrue(any("end must be after start" in p for p in problems_for(mutate(window + ["start"], "23:00"))))

    def test_horizon_bounds(self) -> None:
        self.assertTrue(problems_for(mutate(["slotTemplate", "horizonDays"], 0)))
        self.assertTrue(problems_for(mutate(["slotTemplate", "horizonDays"], 63)))
        self.assertEqual([], problems_for(mutate(["slotTemplate", "horizonDays"], 62)))

    def test_the_same_weekday_and_window_in_two_rules_is_rejected(self) -> None:
        data = sample_manifest()
        data["slotTemplate"]["rules"].append(copy.deepcopy(data["slotTemplate"]["rules"][0]))
        self.assertTrue(any("more than once" in p for p in problems_for(data)))

    def test_all_problems_are_reported_together(self) -> None:
        data = sample_manifest()
        data["products"][0]["basePrice"] = 5
        data["zones"][0]["code"] = "bad"
        data["slotTemplate"]["horizonDays"] = 99
        self.assertGreaterEqual(len(problems_for(data)), 3)

    def test_problems_never_echo_values(self) -> None:
        sentinel = "SENTINEL-VALUE-98765.4321"
        data = sample_manifest()
        data["products"][0]["basePrice"] = sentinel
        data["products"][0]["slug"] = sentinel
        data["zones"][0]["code"] = sentinel
        data["products"][0]["bogus"] = sentinel
        text = "\n".join(problems_for(data))
        self.assertTrue(text)
        self.assertNotIn(sentinel, text)


if __name__ == "__main__":
    unittest.main()
