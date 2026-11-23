"""TLS behaviour of the real command line against a local HTTPS server.

Standard library only, plus the ``openssl`` command line tool to make throwaway certificates.

The CLI runs as a child process, and its own environment alone decides which CA it trusts:
``SSL_CERT_FILE`` is set for the "trusted" case and removed for every other case. The test CA is
never added to, and nothing is changed in, any operating-system or user certificate store.

A refused connection must reach the server as a failed handshake only: zero HTTP requests, and the
bearer token must never be sent. The accepted case is the control that shows the server, the
manifest and the token are otherwise identical.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from collections.abc import Callable
from pathlib import Path
from types import TracebackType

TOOL = Path(__file__).resolve().parent.parent
TOKEN = "tls-test-token-FAKE-7731"
ADMIN = "/api/v1/admin"
CANNED: dict[str, object] = {
    f"{ADMIN}/categories": [],
    f"{ADMIN}/delivery-zones": [],
    f"{ADMIN}/delivery-slots": [],
    f"{ADMIN}/products": {"content": [], "page": 0, "size": 100, "totalElements": 0},
}


def wait_until(condition: Callable[[], bool], timeout: float = 3.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(0.02)
    return condition()


class Pki:
    """A throwaway CA, a second unrelated CA, and two server certificates signed by the first CA."""

    def __init__(self, root: Path, exe: str) -> None:
        self.dir = root

        def run(*args: str) -> None:
            subprocess.run([exe, *args], cwd=root, check=True, capture_output=True)

        key = ["-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes"]
        for name in ("ca", "other-ca"):
            run(
                "req", "-x509", *key, "-days", "2", "-subj", f"/CN=Loader test {name}",
                "-keyout", f"{name}.key", "-out", f"{name}.pem",
                "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            )
        for leaf, names in (("good", "IP:127.0.0.1,DNS:localhost"), ("wrongname", "DNS:other.example.test")):
            run("req", *key, "-subj", "/CN=leaf", "-keyout", f"{leaf}.key", "-out", f"{leaf}.csr")
            (root / f"{leaf}.cnf").write_text(
                f"subjectAltName={names}\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\n", encoding="utf-8"
            )
            run(
                "x509", "-req", "-in", f"{leaf}.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
                "-CAcreateserial", "-days", "2", "-out", f"{leaf}.pem", "-extfile", f"{leaf}.cnf",
            )


class TlsServer:
    """An HTTPS server on 127.0.0.1 that answers the loader's four read requests with empty data."""

    def __init__(self, cert: Path, key: Path) -> None:
        self._context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self._context.load_cert_chain(cert, key)
        self._socket = socket.socket()
        self._socket.bind(("127.0.0.1", 0))
        self._socket.listen(8)
        self._socket.settimeout(0.2)
        self.port: int = self._socket.getsockname()[1]
        self.failed_handshakes = 0
        self.requests: list[str] = []
        self.saw_token = False
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._serve, daemon=True)

    def __enter__(self) -> TlsServer:
        self._thread.start()
        return self

    def __exit__(self, kind: type[BaseException] | None, error: BaseException | None, trace: TracebackType | None) -> None:
        self._stop.set()
        self._thread.join(5)
        self._socket.close()

    def _serve(self) -> None:
        while not self._stop.is_set():
            try:
                connection, _ = self._socket.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            connection.settimeout(5)
            try:
                secure = self._context.wrap_socket(connection, server_side=True)
            except (ssl.SSLError, OSError):
                self.failed_handshakes += 1
                connection.close()
                continue
            try:
                data = secure.recv(65536)
                if data:
                    path = data.split(b" ")[1].split(b"?")[0].decode("latin-1")
                    self.requests.append(path)
                    self.saw_token = self.saw_token or TOKEN.encode() in data
                    body = json.dumps(CANNED.get(path, [])).encode()
                    head = b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n"
                    secure.sendall(head + b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n" + body)
            except OSError:
                pass
            finally:
                secure.close()


@unittest.skipUnless(shutil.which("openssl"), "the openssl command line tool is needed to make test certificates")
class TlsTest(unittest.TestCase):
    pki: Pki
    token_file: Path

    @classmethod
    def setUpClass(cls) -> None:
        tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(tmp.cleanup)
        exe = shutil.which("openssl")
        assert exe is not None
        root = Path(tmp.name)
        cls.pki = Pki(root, exe)
        cls.token_file = root / "token.txt"
        cls.token_file.write_text(TOKEN, encoding="utf-8")
        cls.token_file.chmod(0o600)  # a credential file must be private on POSIX

    def server(self, leaf: str) -> TlsServer:
        return TlsServer(self.pki.dir / f"{leaf}.pem", self.pki.dir / f"{leaf}.key")

    def run_loader(self, port: int, trusted_ca: str | None) -> subprocess.CompletedProcess[str]:
        env = {k: v for k, v in os.environ.items() if k not in ("SSL_CERT_FILE", "SSL_CERT_DIR")}
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        if trusted_ca is not None:
            env["SSL_CERT_FILE"] = str(self.pki.dir / trusted_ca)
        command = [
            sys.executable, "-B", "-m", "catalogue_loader",
            "--manifest", str(TOOL / "examples" / "example-manifest.json"),
            "--base-url", f"https://127.0.0.1:{port}",
            "--access-token-file", str(self.token_file),
        ]
        return subprocess.run(command, cwd=TOOL, env=env, capture_output=True, text=True, timeout=60)

    def assert_refused(self, server: TlsServer, run: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(2, run.returncode)
        self.assertTrue(wait_until(lambda: server.failed_handshakes >= 1), "the client should abort the handshake")
        self.assertEqual([], server.requests)  # zero HTTP requests reached the server
        self.assertFalse(server.saw_token)  # the bearer token was never sent
        self.assertNotIn(TOKEN, run.stdout + run.stderr)
        self.assertIn("did not complete", run.stderr)

    def test_a_certificate_from_an_untrusted_ca_is_rejected(self) -> None:
        with self.server("good") as server:
            self.assert_refused(server, self.run_loader(server.port, trusted_ca=None))

    def test_trusting_a_different_ca_does_not_help(self) -> None:
        with self.server("good") as server:
            self.assert_refused(server, self.run_loader(server.port, trusted_ca="other-ca.pem"))

    def test_a_certificate_for_the_wrong_name_is_rejected_even_from_a_trusted_ca(self) -> None:
        with self.server("wrongname") as server:
            self.assert_refused(server, self.run_loader(server.port, trusted_ca="ca.pem"))

    def test_a_certificate_from_a_trusted_ca_is_accepted(self) -> None:
        with self.server("good") as server:
            run = self.run_loader(server.port, trusted_ca="ca.pem")
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertEqual(0, server.failed_handshakes)
            self.assertEqual(sorted(CANNED), sorted(server.requests))  # all four reads arrived over TLS
            self.assertTrue(server.saw_token)  # sent only once the server was trusted
            self.assertNotIn(TOKEN, run.stdout + run.stderr)  # and never echoed back
            self.assertEqual("OK", json.loads(run.stdout)["result"])


if __name__ == "__main__":
    unittest.main()
