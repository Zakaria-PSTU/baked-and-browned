"""Exceptions and process exit codes of the catalogue loader."""

from __future__ import annotations

EXIT_OK = 0
EXIT_INVALID = 1  # bad usage, manifest, or plan; nothing was written
EXIT_FAILED = 2  # a write failed or conflicted; the run stopped, rerun is safe
EXIT_AUTH = 3  # credentials rejected, or the account is not an administrator
EXIT_DRIFT = 4  # existing records differ from the manifest; they were left alone


class LoaderError(Exception):
    """A failure the operator can act on. Messages never contain secrets or values."""

    exit_code = EXIT_INVALID


class UsageError(LoaderError):
    """Bad command line, unsafe target, or unusable credential file."""


class ManifestError(LoaderError):
    """The manifest is invalid. ``problems`` name paths and rules, never values."""

    def __init__(self, problems: list[str]) -> None:
        super().__init__(f"{len(problems)} manifest problem(s)")
        self.problems = problems


class AuthError(LoaderError):
    """Login failed, or the token was rejected."""

    exit_code = EXIT_AUTH


class ProtocolError(LoaderError):
    """The API answered with a shape the loader does not understand."""

    exit_code = EXIT_FAILED


class TransportError(Exception):
    """No HTTP response arrived (network failure or timeout)."""


class ApiError(Exception):
    """A non-success HTTP answer. Only the status and the API's error code are kept."""

    def __init__(self, status: int, code: str) -> None:
        super().__init__(f"HTTP {status} {code}")
        self.status = status
        self.code = code


class WriteBlockedError(Exception):
    """A write was attempted by a client that is not allowed to write (dry-run guard)."""
