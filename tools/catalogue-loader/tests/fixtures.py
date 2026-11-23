"""Shared test helpers. Every name, code, price and address here is fictional."""

from __future__ import annotations

import io
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from catalogue_loader import cli

from .fakes import ADMIN_TOKEN, FakeApi

# Monday 2026-11-23, 09:00 in Dhaka (UTC+06:00).
NOW = datetime(2026, 11, 23, 3, 0, tzinfo=timezone.utc)

ALLOWED_METHODS = {"GET", "POST"}


def sample_manifest() -> dict[str, Any]:
    """A complete manifest of invented records. 12 template days x 2 windows = 24 slots."""
    return {
        "version": 1,
        "categories": [
            {"slug": "example-cakes", "name": "Example Cakes", "sortOrder": 10, "active": True}
        ],
        "products": [
            {
                "slug": "example-fudge-cake",
                "name": "Example Fudge Cake",
                "category": "example-cakes",
                "description": "A fictional cake.",
                "basePrice": "123.45",
                "minLeadHours": 24,
                "productionUnits": 2,
                "status": "DRAFT",
            }
        ],
        "zones": [
            {"code": "EXAMPLE_NORTH", "name": "Example North", "deliveryFee": "11.00",
             "minOrderAmount": "50.00", "active": True, "sortOrder": 10},
            {"code": "EXAMPLE_SOUTH", "name": "Example South", "active": False},
        ],
        "slotTemplate": {
            "horizonDays": 14,
            "rules": [
                {
                    "weekdays": ["mon", "tue", "wed", "thu", "fri", "sat"],
                    "windows": [
                        {"start": "10:00", "end": "12:00", "capacityUnits": 10},
                        {"start": "14:00", "end": "16:00", "capacityUnits": 8},
                    ],
                }
            ],
        },
    }


class Result:
    def __init__(self, code: int, out: str, err: str, report: dict[str, Any] | None) -> None:
        self.code = code
        self.out = out
        self.err = err
        self.report = report

    def ops(self, kind: str) -> dict[str, int]:
        counts: dict[str, int] = {}
        for action in (self.report or {}).get("actions", []):
            if action["kind"] == kind:
                label = f"{action['op']}/{action['outcome']}"
                counts[label] = counts.get(label, 0) + 1
        return counts


class LoaderTestCase(unittest.TestCase):
    """A fresh fake API and temp directory per test; checks the no-update rule afterwards."""

    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.fake = FakeApi(NOW)
        self.token_file = self._write("token.txt", ADMIN_TOKEN)

    def tearDown(self) -> None:
        # Evidence for "no full-replacement update is ever issued": whatever a test did, the
        # loader used only GET and POST.
        methods = {method for method, _ in self.fake.calls}
        self.assertLessEqual(methods, ALLOWED_METHODS, "the loader issued an update or delete")

    def _write(self, name: str, text: str) -> Path:
        path = self.tmp / name
        path.write_text(text, encoding="utf-8")
        return path

    def run_cli(
        self,
        manifest: dict[str, Any] | None = None,
        *,
        apply: bool = False,
        now: datetime = NOW,
        extra: list[str] | None = None,
        token_file: Path | None = None,
        base_url: str = "https://staging.example.test",
        transport: Any = None,
        use_default_credentials: bool = True,
    ) -> Result:
        manifest_path = self._write("manifest.json", json.dumps(manifest if manifest is not None else sample_manifest()))
        args = ["--manifest", str(manifest_path), "--base-url", base_url]
        if use_default_credentials:
            args += ["--access-token-file", str(token_file or self.token_file)]
        if apply:
            args += ["--apply", "--confirm-target", "staging.example.test"]
        args += extra or []
        out, err = io.StringIO(), io.StringIO()
        code = cli.main(args, transport=transport or self.fake.transport, now=now, stdout=out, stderr=err)
        report: dict[str, Any] | None = None
        if out.getvalue().strip().startswith("{"):
            report = json.loads(out.getvalue())
        return Result(code, out.getvalue(), err.getvalue(), report)
