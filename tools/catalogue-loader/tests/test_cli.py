"""Command line: safeguards, authentication failures, secret redaction, and the real HTTP path."""

from __future__ import annotations

import contextlib
import http.server
import json
import os
import socket
import threading
import unittest
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from catalogue_loader.client import LOGIN_PATH, HttpRequest, HttpResponse, urllib_transport
from catalogue_loader.report import REDACTED, Redactor, build_report, dump_report

from .fakes import ADMIN_TOKEN, CUSTOMER_TOKEN, IDENTIFIER, PASSWORD, FakeApi
from .fixtures import NOW, LoaderTestCase, sample_manifest

ADMIN = "/api/v1/admin"

# Values that must never appear in any output: they are credentials, personal data, or prices
# and names from the manifest.
NEVER_PRINTED = [
    ADMIN_TOKEN, CUSTOMER_TOKEN, PASSWORD, IDENTIFIER, "wrong-password-SENTINEL",
    "123.45", "11.00", "50.00", "Example Fudge Cake", "Example Cakes", "Example North",
    "A fictional cake", "Bearer ",
]


def never_blocked(_: HttpRequest) -> HttpResponse:
    raise AssertionError("the network must not be used")


class SafeguardTest(LoaderTestCase):
    def test_update_is_refused_with_the_reason_and_nothing_is_sent(self) -> None:
        result = self.run_cli(extra=["--update"])
        self.assertEqual(1, result.code)
        self.assertIn("not available in v1", result.err)
        self.assertEqual([], self.fake.calls)

    def test_validate_only_needs_no_credentials_and_no_network(self) -> None:
        result = self.run_cli(extra=["--validate-only"], transport=never_blocked, use_default_credentials=False)
        self.assertEqual(0, result.code)
        self.assertIn("valid", result.err)

    def test_an_invalid_manifest_stops_before_any_request(self) -> None:
        manifest = sample_manifest()
        manifest["products"][0]["basePrice"] = 12.5
        manifest["zones"][0]["code"] = "bad code"
        result = self.run_cli(manifest, apply=True)
        self.assertEqual(1, result.code)
        self.assertEqual([], self.fake.calls)
        self.assertGreaterEqual(result.err.count("manifest:"), 2)  # every problem, not just the first

    def test_a_missing_manifest_file_is_a_clean_failure(self) -> None:
        import io

        from catalogue_loader import cli

        err = io.StringIO()
        code = cli.main(["--manifest", str(self.tmp / "absent.json"), "--validate-only"], stderr=err)
        self.assertEqual(1, code)
        self.assertIn("cannot be read", err.getvalue())

    def test_plain_http_to_a_remote_host_is_refused(self) -> None:
        for flags in ([], ["--allow-insecure-local"]):
            result = self.run_cli(base_url="http://staging.example.test", extra=flags)
            self.assertEqual(1, result.code)
            self.assertEqual([], self.fake.calls)

    def test_plain_http_to_localhost_needs_the_explicit_flag(self) -> None:
        self.assertEqual(1, self.run_cli(base_url="http://127.0.0.1:8080").code)
        self.assertEqual([], self.fake.calls)
        self.assertEqual(0, self.run_cli(base_url="http://127.0.0.1:8080", extra=["--allow-insecure-local"]).code)

    def test_the_url_must_be_an_origin_without_credentials_path_or_query(self) -> None:
        for url in ("https://user:pw@staging.example.test", "https://staging.example.test/api",
                    "https://staging.example.test?x=1", "https://staging.example.test#frag", "ftp://x.test", "nonsense", ""):
            with self.subTest(url=url):
                result = self.run_cli(base_url=url)
                self.assertEqual(1, result.code)
                self.assertEqual([], self.fake.calls)
        self.assertNotIn("pw@", self.run_cli(base_url="https://user:pw@staging.example.test").err)

    def test_applying_to_a_remote_host_needs_the_host_typed_exactly(self) -> None:
        for confirm in ([], ["--confirm-target", "other.example.test"], ["--confirm-target", "STAGING.example.test"]):
            with self.subTest(confirm=confirm):
                result = self.run_cli(extra=["--apply", *confirm])
                self.assertEqual(1, result.code)
                self.assertEqual([], self.fake.calls)
                self.assertIn("--confirm-target", result.err)

    def test_the_typed_host_does_not_authorize_a_dry_run_to_write(self) -> None:
        self.run_cli(extra=["--confirm-target", "staging.example.test"])  # no --apply
        self.assertEqual([], self.fake.writes)

    def test_applying_to_localhost_needs_no_typed_host(self) -> None:
        result = self.run_cli(base_url="http://127.0.0.1:8080", extra=["--allow-insecure-local", "--apply"])
        self.assertEqual(0, result.code)
        self.assertEqual(1, len(self.fake.categories))

    def test_exactly_one_credential_mode_is_required(self) -> None:
        cases = {
            "none": ([], False),
            "both": (["--identifier", IDENTIFIER, "--password-file", str(self._write("pw.txt", PASSWORD, secret=True))], True),
            "identifier only": (["--identifier", IDENTIFIER], False),
            "password only": (["--password-file", str(self._write("pw.txt", PASSWORD, secret=True))], False),
        }
        for name, (extra, with_token) in cases.items():
            with self.subTest(name):
                result = self.run_cli(extra=extra, use_default_credentials=with_token)
                self.assertEqual(1, result.code)
                self.assertEqual([], self.fake.calls)

    def test_a_missing_or_empty_credential_file_is_refused_without_a_request(self) -> None:
        self.assertEqual(1, self.run_cli(token_file=self.tmp / "absent.txt").code)
        self.assertEqual(1, self.run_cli(token_file=self._write("empty.txt", "  \n", secret=True)).code)
        self.assertEqual([], self.fake.calls)

    @unittest.skipUnless(os.name == "posix", "file modes are only meaningful on POSIX")
    def test_a_token_file_readable_by_group_or_others_is_refused(self) -> None:
        for mode in (0o644, 0o640, 0o604, 0o660, 0o666, 0o710):
            with self.subTest(mode=oct(mode)):
                self.token_file.chmod(mode)
                result = self.run_cli()
                self.assertEqual(1, result.code)
                self.assertIn("0600", result.err)
                self.assertEqual([], self.fake.calls)  # refused before any request
        for mode in (0o600, 0o400):
            with self.subTest(mode=oct(mode)):
                self.token_file.chmod(mode)
                self.assertEqual(0, self.run_cli().code)

    @unittest.skipUnless(os.name == "posix", "file modes are only meaningful on POSIX")
    def test_a_password_file_readable_by_group_or_others_is_refused(self) -> None:
        password_file = self._write("pw.txt", PASSWORD, secret=True)
        login = ["--identifier", IDENTIFIER, "--password-file", str(password_file)]
        for mode in (0o644, 0o640, 0o604):
            with self.subTest(mode=oct(mode)):
                password_file.chmod(mode)
                result = self.run_cli(extra=login, use_default_credentials=False)
                self.assertEqual(1, result.code)
                self.assertEqual([], self.fake.calls)  # not even a login attempt
        for mode in (0o600, 0o400):
            with self.subTest(mode=oct(mode)):
                password_file.chmod(mode)
                self.assertEqual(0, self.run_cli(extra=login, use_default_credentials=False).code)


class AuthenticationTest(LoaderTestCase):
    def _login_args(self, password: str = PASSWORD) -> list[str]:
        return ["--identifier", IDENTIFIER, "--password-file", str(self._write("password.txt", password, secret=True))]

    def test_password_login_exchanges_credentials_for_a_token(self) -> None:
        result = self.run_cli(extra=self._login_args(), use_default_credentials=False)
        self.assertEqual(0, result.code)
        self.assertEqual(("POST", LOGIN_PATH), self.fake.calls[0])
        self.assertEqual([], self.fake.writes)

    def test_a_wrong_password_is_exit_3_and_no_admin_endpoint_is_touched(self) -> None:
        result = self.run_cli(extra=self._login_args("wrong-password-SENTINEL"), use_default_credentials=False)
        self.assertEqual(3, result.code)
        self.assertEqual([("POST", LOGIN_PATH)], self.fake.calls)
        self.assertEqual("AUTH_FAILED", (result.report or {})["result"])

    def test_a_rate_limited_login_is_an_auth_failure(self) -> None:
        self.fake.fail("POST", LOGIN_PATH, 429, "RATE_LIMITED")
        self.assertEqual(3, self.run_cli(extra=self._login_args(), use_default_credentials=False).code)

    def test_a_rejected_token_is_exit_3(self) -> None:
        result = self.run_cli(token_file=self._write("bad.txt", "not-a-real-token", secret=True))
        self.assertEqual(3, result.code)
        self.assertEqual([], self.fake.writes)

    def test_a_token_without_the_admin_role_is_exit_3(self) -> None:
        result = self.run_cli(apply=True, token_file=self._write("customer.txt", CUSTOMER_TOKEN, secret=True))
        self.assertEqual(3, result.code)
        self.assertEqual([], self.fake.writes)

    def test_a_token_that_expires_mid_run_stops_cleanly_with_exit_3(self) -> None:
        self.fake.fail("POST", f"{ADMIN}/products", 401, "UNAUTHORIZED")
        result = self.run_cli(apply=True)
        self.assertEqual(3, result.code)
        self.assertEqual({"CREATE/CREATED": 1}, result.ops("category"))
        self.assertEqual({"CREATE/FAILED": 1}, result.ops("product"))
        self.assertEqual({"CREATE/NOT_RUN": 2}, result.ops("zone"))
        self.assertEqual((1, 0, 0, 0), (len(self.fake.categories), len(self.fake.products),
                                         len(self.fake.zones), len(self.fake.slots)))


class RedactionTest(LoaderTestCase):
    def _assert_clean(self, *texts: str) -> None:
        for text in texts:
            for secret in NEVER_PRINTED:
                self.assertNotIn(secret, text, f"{secret!r} leaked")

    def test_a_successful_run_prints_no_secret_name_or_price(self) -> None:
        report_file = self.tmp / "report.json"
        result = self.run_cli(apply=True, extra=["--report-file", str(report_file)])
        self.assertEqual(0, result.code)
        self._assert_clean(result.out, result.err, report_file.read_text(encoding="utf-8"))

    def test_a_server_that_reflects_secrets_in_its_errors_does_not_leak_them(self) -> None:
        self.fake.echo_secrets_in_errors = True
        self.fake.fail("POST", f"{ADMIN}/delivery-zones", 500, "INTERNAL_ERROR")
        result = self.run_cli(apply=True)
        self.assertEqual(2, result.code)
        self._assert_clean(result.out, result.err)

    def test_a_failed_login_prints_neither_the_password_nor_the_identifier(self) -> None:
        self.fake.echo_secrets_in_errors = True
        password_file = self._write("password.txt", "wrong-password-SENTINEL", secret=True)
        result = self.run_cli(extra=["--identifier", IDENTIFIER, "--password-file", str(password_file)],
                              use_default_credentials=False)
        self.assertEqual(3, result.code)
        self._assert_clean(result.out, result.err)

    def test_a_dry_run_report_names_keys_and_outcomes_but_no_names_or_prices(self) -> None:
        result = self.run_cli()
        self._assert_clean(result.out, result.err)
        assert result.report is not None
        self.assertEqual({"EXAMPLE_NORTH", "EXAMPLE_SOUTH"},
                         {a["key"] for a in result.report["actions"] if a["kind"] == "zone"})
        self.assertEqual("staging.example.test", result.report["target"]["host"])

    def test_the_redactor_replaces_longest_secrets_first(self) -> None:
        redactor = Redactor(["abc", "abcdef"])
        self.assertEqual(f"x {REDACTED} y", redactor.redact("x abcdef y"))
        self.assertEqual("nothing here", Redactor(["", "zzz"]).redact("nothing here"))

    def test_the_final_report_text_is_redacted_even_in_free_text_fields(self) -> None:
        from datetime import datetime, timezone
        redactor = Redactor(["TOP-SECRET-VALUE"])
        report = build_report(mode="dry-run", target_host="h", started=datetime.now(timezone.utc),
                              finished=datetime.now(timezone.utc), plan=None, exit_code=2,
                              error="boom TOP-SECRET-VALUE boom")
        self.assertNotIn("TOP-SECRET-VALUE", dump_report(report, redactor))

    def test_the_report_file_is_written_and_stdout_stays_empty(self) -> None:
        path = self.tmp / "out.json"
        result = self.run_cli(extra=["--report-file", str(path)])
        self.assertEqual("", result.out)
        self.assertEqual("OK", json.loads(path.read_text(encoding="utf-8"))["result"])

    def test_an_unwritable_report_file_does_not_hide_the_result(self) -> None:
        result = self.run_cli(extra=["--report-file", str(self.tmp / "no-such-dir" / "r.json")])
        self.assertIn("cannot be written", result.err)
        self.assertEqual(2, result.code)  # a run whose report was lost is not a clean success
        self.assertIn('"result"', result.out)  # the report is still shown


# ---------------------------------------------------------------------------- real HTTP


@contextlib.contextmanager
def serve(handler_cls: type[http.server.BaseHTTPRequestHandler]) -> Iterator[int]:
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler_cls)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield int(server.server_address[1])
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def fake_handler(fake: FakeApi) -> type[http.server.BaseHTTPRequestHandler]:
    class Handler(http.server.BaseHTTPRequestHandler):
        def _handle(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else None
            headers = {k: v for k, v in self.headers.items()}
            url = f"http://127.0.0.1{self.path}"
            response = fake.transport(HttpRequest(self.command, url, headers, body, 5.0))
            self.send_response(response.status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response.body)))
            self.end_headers()
            self.wfile.write(response.body)

        do_GET = do_POST = do_PATCH = do_PUT = do_DELETE = _handle

        def log_message(self, format: str, *args: Any) -> None:
            return

    return Handler


class RealHttpTest(LoaderTestCase):
    """The default urllib transport, against a real socket on 127.0.0.1."""

    def _run(self, port: int, *, apply: bool = False) -> Any:
        return self.run_cli(base_url=f"http://127.0.0.1:{port}", apply=False,
                            extra=["--allow-insecure-local", *(["--apply"] if apply else [])],
                            transport=urllib_transport)

    def test_a_dry_run_and_an_apply_over_real_http(self) -> None:
        with serve(fake_handler(self.fake)) as port:
            dry = self._run(port)
            self.assertEqual(0, dry.code)
            self.assertEqual([], self.fake.writes)
            applied = self._run(port, apply=True)
            self.assertEqual(0, applied.code)
            self.assertEqual((1, 1, 2, 24), (len(self.fake.categories), len(self.fake.products),
                                             len(self.fake.zones), len(self.fake.slots)))
            self.assertEqual(0, self._run(port, apply=True).code)  # and again: no change
            self.assertEqual(1 + 1 + 2 + 24, len(self.fake.writes))

    def test_a_redirect_is_never_followed_so_the_token_cannot_be_forwarded(self) -> None:
        seen: list[str] = []

        class Target(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                seen.append(self.headers.get("Authorization", ""))
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"[]")

            def log_message(self, format: str, *args: Any) -> None:
                return

        with serve(Target) as target_port:
            class Redirector(http.server.BaseHTTPRequestHandler):
                def do_GET(self) -> None:
                    self.send_response(302)
                    self.send_header("Location", f"http://127.0.0.1:{target_port}/steal")
                    self.send_header("Content-Length", "0")
                    self.end_headers()

                def log_message(self, format: str, *args: Any) -> None:
                    return

            with serve(Redirector) as port:
                result = self._run(port)
        self.assertEqual(2, result.code)
        self.assertEqual([], seen)  # the second server never received a request, or the token
        self._not_leaked(result)

    def test_a_refused_connection_is_a_clean_failure(self) -> None:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            closed_port = probe.getsockname()[1]
        result = self._run(closed_port)
        self.assertEqual(2, result.code)
        self.assertIn("did not complete", result.err)
        self._not_leaked(result)

    def test_a_server_that_answers_html_is_a_clean_failure(self) -> None:
        class Html(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                body = b"<html>maintenance</html>"
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format: str, *args: Any) -> None:
                return

        with serve(Html) as port:
            result = self._run(port)
        self.assertEqual(2, result.code)

    def _not_leaked(self, result: Any) -> None:
        for secret in (ADMIN_TOKEN, "Bearer "):
            self.assertNotIn(secret, result.out + result.err)


if __name__ == "__main__":
    unittest.main()
