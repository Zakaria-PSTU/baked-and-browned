"""Structured run reports, and the redaction that keeps secrets out of every output.

A report names records by natural key (category slug, product slug, zone code, slot start and
end) and by outcome. It never contains a name, a price, a fee, a description, a phone number, an
e-mail address, a token, or a password.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterable
from datetime import datetime, timezone
from typing import Any

from .client import JsonObject
from .errors import EXIT_AUTH, EXIT_DRIFT, EXIT_FAILED, EXIT_INVALID, EXIT_OK
from .planner import Plan

REPORT_VERSION = 1
REDACTED = "[REDACTED]"

_RESULTS = {
    EXIT_OK: "OK",
    EXIT_INVALID: "INVALID",
    EXIT_FAILED: "FAILED",
    EXIT_AUTH: "AUTH_FAILED",
    EXIT_DRIFT: "DRIFT",
}


class Redactor:
    """Replaces known secret strings in any text that is about to be shown or written."""

    def __init__(self, secrets: Iterable[str] = ()) -> None:
        self._secrets: set[str] = set()
        for secret in secrets:
            self.add(secret)

    def add(self, secret: str) -> None:
        if secret:
            self._secrets.add(secret)

    def redact(self, text: str) -> str:
        # Longest first, so a secret that contains another is replaced whole.
        for secret in sorted(self._secrets, key=len, reverse=True):
            text = text.replace(secret, REDACTED)
        return text


def _iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_report(
    *,
    mode: str,
    target_host: str,
    started: datetime,
    finished: datetime,
    plan: Plan | None,
    exit_code: int,
    error: str | None = None,
) -> JsonObject:
    actions: list[JsonObject] = []
    summary: dict[str, dict[str, int]] = {}
    if plan is not None:
        for action in plan.actions:
            entry: JsonObject = {
                "kind": action.kind,
                "key": action.key,
                "op": action.op,
                "outcome": action.outcome,
            }
            if action.fields:
                entry["fields"] = list(action.fields)
            if action.error_code is not None:
                entry["errorCode"] = action.error_code
            actions.append(entry)
            bucket = summary.setdefault(action.kind, {})
            bucket[action.op] = bucket.get(action.op, 0) + 1
            if action.outcome != "PLANNED":
                bucket[action.outcome] = bucket.get(action.outcome, 0) + 1
    report: JsonObject = {
        "tool": "catalogue-loader",
        "reportVersion": REPORT_VERSION,
        "runId": uuid.uuid4().hex,
        "mode": mode,
        "target": {"host": target_host},
        "startedAt": _iso(started),
        "finishedAt": _iso(finished),
        "result": _RESULTS.get(exit_code, "FAILED"),
        "exitCode": exit_code,
        "summary": summary,
        "actions": actions,
    }
    if plan is not None and plan.expansion is not None:
        report["slotWindow"] = {
            "from": plan.expansion.date_from.isoformat(),
            "to": plan.expansion.date_to.isoformat(),
            "skippedStarted": plan.expansion.skipped_past,
            "existingOutsideTemplate": plan.outside_template_slots,
        }
    if error is not None:
        report["error"] = error
    return report


def dump_report(report: JsonObject, redactor: Redactor) -> str:
    """Serialize, then redact the final text, so a secret cannot slip in through any field."""
    text = json.dumps(_plain(report), indent=2, sort_keys=True) + "\n"
    return redactor.redact(text)


def _plain(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(k): _plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    return value
