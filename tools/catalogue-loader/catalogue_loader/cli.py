"""Command line: dry run by default; writes need --apply; remote writes need a typed host."""

from __future__ import annotations

import argparse
import os
import stat
import sys
import urllib.parse
from collections.abc import Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import TextIO

from .client import ApiClient, Transport, urllib_transport
from .errors import (
    EXIT_FAILED,
    EXIT_INVALID,
    AuthError,
    LoaderError,
    ManifestError,
    UsageError,
    WriteBlockedError,
)
from .manifest import Manifest, load_manifest
from .planner import Engine, Plan, exit_code_for
from .report import Redactor, build_report, dump_report

LOCAL_HOSTS = ("localhost", "127.0.0.1", "::1")

UPDATE_REFUSAL = (
    "--update is not available in v1. The backend's PATCH endpoints replace a whole record and "
    "carry no version, so an update cannot be made safe against a concurrent edit without a "
    "backend change. Existing records that differ from the manifest are reported as DRIFT and "
    "left alone; change them in the admin API deliberately."
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="catalogue_loader",
        description=(
            "Create the product catalogue, delivery zones and delivery slots described by a "
            "manifest, through the admin API. Create-only; a dry run unless --apply is given."
        ),
    )
    parser.add_argument("--manifest", required=True, type=Path, help="path to the JSON manifest")
    parser.add_argument("--validate-only", action="store_true", help="validate the manifest and exit; no network")
    parser.add_argument("--base-url", help="origin of the API, for example https://staging.example")
    parser.add_argument("--access-token-file", type=Path, help="file holding an ADMIN access token")
    parser.add_argument("--identifier", help="login identifier (with --password-file)")
    parser.add_argument("--password-file", type=Path, help="file holding the login password")
    parser.add_argument("--apply", action="store_true", help="perform the creates (default is a dry run)")
    parser.add_argument("--confirm-target", help="the target host, typed out; required to --apply to a remote host")
    parser.add_argument("--allow-insecure-local", action="store_true", help="allow http:// to localhost only")
    parser.add_argument("--report-file", type=Path, help="write the JSON report here (default: stdout)")
    parser.add_argument("--update", action="store_true", help="not available in v1; refused")
    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    transport: Transport = urllib_transport,
    now: datetime | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
) -> int:
    out = stdout if stdout is not None else sys.stdout
    err = stderr if stderr is not None else sys.stderr
    redactor = Redactor()
    try:
        return _run(build_parser().parse_args(argv), transport, now, out, err, redactor)
    except ManifestError as problem:
        for line in problem.problems:
            print(redactor.redact(f"manifest: {line}"), file=err)
        return problem.exit_code
    except LoaderError as problem:
        print(redactor.redact(f"error: {problem}"), file=err)
        return problem.exit_code
    except WriteBlockedError:
        print("internal error: a write was attempted in a read-only run", file=err)
        return EXIT_FAILED


def _run(
    args: argparse.Namespace,
    transport: Transport,
    now: datetime | None,
    out: TextIO,
    err: TextIO,
    redactor: Redactor,
) -> int:
    if args.update:
        print(UPDATE_REFUSAL, file=err)
        return EXIT_INVALID
    manifest = load_manifest(args.manifest)
    if args.validate_only:
        print("manifest is valid", file=err)
        return 0
    base_url, host = _check_target(args)
    token, identifier, password = _credentials(args, redactor)
    started = now if now is not None else datetime.now(timezone.utc)
    mode = "apply" if args.apply else "dry-run"
    print(f"target {host}, mode {mode}", file=err)

    client = ApiClient(base_url, transport, token=token, allow_writes=bool(args.apply))
    plan: Plan | None = None
    error: str | None = None
    try:
        if token is None:
            assert identifier is not None and password is not None
            redactor.add(client.login(identifier, password))
        plan = _execute(client, manifest, started, bool(args.apply))
        code = exit_code_for(plan)
    except LoaderError as problem:
        code = problem.exit_code
        error = redactor.redact(str(problem))
        print(f"error: {error}", file=err)
    return _finish(args, out, err, redactor, mode, host, started, plan, code, error)


def _execute(client: ApiClient, manifest: Manifest, now: datetime, apply: bool) -> Plan:
    engine = Engine(client, manifest, now)
    plan = engine.plan()
    if plan.invalid:
        return plan  # nothing is written when any record cannot be applied
    if apply:
        engine.apply(plan)
    return plan


def _finish(
    args: argparse.Namespace,
    out: TextIO,
    err: TextIO,
    redactor: Redactor,
    mode: str,
    host: str,
    started: datetime,
    plan: Plan | None,
    code: int,
    error: str | None,
) -> int:
    finished = datetime.now(timezone.utc)
    report = build_report(
        mode=mode, target_host=host, started=started, finished=max(started, finished),
        plan=plan, exit_code=code, error=error,
    )
    text = dump_report(report, redactor)
    if args.report_file is not None:
        try:
            args.report_file.write_text(text, encoding="utf-8")
        except OSError:
            print("error: the report file cannot be written", file=err)
            print(text, file=out, end="")
            return EXIT_FAILED if code == 0 else code
    else:
        print(text, file=out, end="")
    if plan is not None:
        _summarize(plan, err)
    return code


def _summarize(plan: Plan, err: TextIO) -> None:
    counts: dict[str, int] = {}
    for action in plan.actions:
        label = action.op if action.outcome == "PLANNED" else f"{action.op}/{action.outcome}"
        counts[label] = counts.get(label, 0) + 1
    print("summary: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())), file=err)


# ---------------------------------------------------------------------------- safeguards


def _check_target(args: argparse.Namespace) -> tuple[str, str]:
    """Validate the API origin. Returns (base_url, host)."""
    if not args.base_url:
        raise UsageError("--base-url is required")
    parts = urllib.parse.urlsplit(args.base_url)
    host = parts.hostname or ""
    if parts.scheme not in ("http", "https") or not host:
        raise UsageError("--base-url must be an http(s) origin")
    if parts.username or parts.password or parts.query or parts.fragment or parts.path not in ("", "/"):
        raise UsageError("--base-url must be an origin only: no credentials, path, query or fragment")
    local = host in LOCAL_HOSTS
    if parts.scheme == "http" and not (local and args.allow_insecure_local):
        raise UsageError("http:// is allowed only for localhost, and only with --allow-insecure-local")
    if args.apply and not local and args.confirm_target != host:
        raise UsageError(
            f"applying to a remote host needs --confirm-target {host} (typed exactly); "
            "nothing was sent"
        )
    port = f":{parts.port}" if parts.port else ""
    return f"{parts.scheme}://{parts.netloc.rsplit('@', 1)[-1]}", host + port


def _credentials(
    args: argparse.Namespace, redactor: Redactor
) -> tuple[str | None, str | None, str | None]:
    """(token, identifier, password): either a token, or an identifier and a password."""
    has_token = args.access_token_file is not None
    has_login = args.password_file is not None or args.identifier is not None
    if has_token == has_login:
        raise UsageError(
            "give either --access-token-file, or --identifier with --password-file (not both)"
        )
    if has_token:
        token = _read_secret(args.access_token_file)
        redactor.add(token)
        return token, None, None
    if args.password_file is None or not args.identifier:
        raise UsageError("--identifier and --password-file must be given together")
    password = _read_secret(args.password_file)
    redactor.add(password)
    redactor.add(args.identifier)
    return None, args.identifier, password


def _read_secret(path: Path) -> str:
    try:
        info = path.stat()
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        raise UsageError("a credential file cannot be read") from None
    if os.name == "posix" and info.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise UsageError("a credential file must not be readable by group or others (use mode 0600)")
    secret = text.strip()
    if not secret:
        raise UsageError("a credential file is empty")
    return secret
