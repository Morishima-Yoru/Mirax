"""Serve the standby prototype and durable, machine-local candidate reviews."""

from __future__ import annotations

import argparse
from ipaddress import IPv6Address
import json
import logging
import os
from pathlib import Path
import re
import socket
import tempfile
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from typing import Final
from urllib.parse import urlsplit


API_PATH: Final[str] = "/api/d-reviews"
MAX_REQUEST_BYTES: Final[int] = 16384
MAX_NOTE_CODE_POINTS: Final[int] = 2000
REJECTION_DRAIN_SECONDS: Final[float] = 0.5
STUDY_FILENAME: Final[re.Pattern[str]] = re.compile(
    r"[0-9]{2,3}-[a-z0-9]+(?:-[a-z0-9]+)*\.js"
)
STUDY_ID: Final[re.Pattern[str]] = re.compile(
    r"(?:study-[0-9]{2,3}-[a-z0-9]+(?:-[a-z0-9]+)*|"
    r"concept-[a-z0-9]+(?:-[a-z0-9]+)*)"
)
CONCEPT_IDS: Final[frozenset[str]] = frozenset({
    "concept-a", "concept-b", "concept-c", "concept-e",
    "concept-c-rail", "concept-c-frame", "concept-c-cap", "concept-c-index",
    "concept-c-footnote", "concept-c-bracket", "concept-c-telemetry",
    "concept-c-stamp", "concept-c-terminal", "concept-c-fold",
    "concept-d-off", "concept-d-signal", "concept-d-scan", "concept-d-quiet",
})
LOGGER: Final[logging.Logger] = logging.getLogger(__name__)
type ReviewSnapshot = dict[str, int | dict[str, str]]


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """Reject ambiguous JSON objects containing duplicate keys."""
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def normalize_https_origin(value: str) -> tuple[str, int]:
    """Validate a single HTTPS origin and normalize its host and default port.

    Args:
        value: HTTPS origin without credentials, path, query, or fragment.

    Returns:
        Normalized host and effective port.

    Raises:
        ValueError: If the value is not a single valid HTTPS origin.
    """
    message = (
        "Expected one HTTPS origin with a valid host/port and no credentials, "
        "path, query, or fragment"
    )
    if not re.fullmatch(
        r"https://(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        r"(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\.?"
        r"|\[[0-9a-f:.]+\])(?::[0-9]+)?", value, re.IGNORECASE | re.ASCII,
    ):
        raise ValueError(message)
    try:
        origin = urlsplit(value)
        host = origin.hostname or ""
        if ":" in host:
            host = str(IPv6Address(host))
        return host, origin.port if origin.port is not None else 443
    except ValueError as error:
        raise ValueError(message) from error


class ReviewStore:
    """Serialize updates and atomically replace a flushed JSON file."""

    def __init__(self, path: Path, studies_directory: Path) -> None:
        """Discover current study IDs without creating a review file.

        Args:
            path: Destination for the machine-local JSON snapshot.
            studies_directory: Directory containing numbered study scripts.
        """
        self.path = path
        discovered = {
            f"study-{script.stem}"
            for script in studies_directory.glob("*.js")
            if script.is_file() and STUDY_FILENAME.fullmatch(script.name)
        }
        self.study_ids = frozenset(discovered | CONCEPT_IDS)
        self._lock = threading.Lock()

    def read(self) -> ReviewSnapshot:
        """Read validated reviews, or empty votes when the file is missing."""
        with self._lock:
            votes, notes = self._read_storage()
            snapshot: ReviewSnapshot = {"schemaVersion": 1, "votes": votes}
            if notes:
                snapshot["notes"] = notes
            return snapshot

    def update(self, study_id: str, vote: str | None) -> ReviewSnapshot:
        """Preserve other votes and return only after an atomic, flushed save.

        Args:
            study_id: A writable concept or current numbered candidate ID.
            vote: Keep, reject, or None to remove the rating.

        Returns:
            The complete saved snapshot.
        """
        if (
            study_id not in self.study_ids
            or vote not in ("keep", "reject", None)
        ):
            raise ValueError("Invalid study ID or vote")
        with self._lock:
            votes, notes = self._read_storage()
            if vote is None:
                votes.pop(study_id, None)
            else:
                votes[study_id] = vote
            snapshot: ReviewSnapshot = {"schemaVersion": 1, "votes": votes}
            if notes:
                snapshot["notes"] = notes
            self._save(snapshot)
            return snapshot

    def update_note(self, study_id: str, note: str) -> ReviewSnapshot:
        """Preserve other reviews and return only after an atomic save.

        Args:
            study_id: A writable concept or current numbered candidate ID.
            note: Candidate note text (up to 2000 chars), or empty to remove.

        Returns:
            The complete saved snapshot.
        """
        if (
            study_id not in self.study_ids
            or not isinstance(note, str)
            or len(note) > MAX_NOTE_CODE_POINTS
        ):
            raise ValueError("Invalid study ID or note")
        with self._lock:
            votes, notes = self._read_storage()
            if not note:
                notes.pop(study_id, None)
            else:
                notes[study_id] = note
            snapshot: ReviewSnapshot = {"schemaVersion": 1, "votes": votes}
            if notes:
                snapshot["notes"] = notes
            self._save(snapshot)
            return snapshot

    def _read_storage(self) -> tuple[dict[str, str], dict[str, str]]:
        """Reject malformed storage instead of resetting votes or notes."""
        try:
            content = self.path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return {}, {}
        payload: object = json.loads(content, object_pairs_hook=unique_object)
        if (
            not isinstance(payload, dict)
            or set(payload) not in ({"schemaVersion", "votes"},
                                    {"schemaVersion", "votes", "notes"})
            or type(payload["schemaVersion"]) is not int
            or payload["schemaVersion"] != 1
            or not isinstance(payload["votes"], dict)
        ):
            raise ValueError("Invalid review storage schema")
        votes = payload["votes"]
        if any(
            not isinstance(key, str)
            or not STUDY_ID.fullmatch(key)
            or value not in ("keep", "reject")
            for key, value in votes.items()
        ):
            raise ValueError("Invalid stored review vote")
        notes: dict[str, str] = {}
        if "notes" in payload:
            stored_notes = payload["notes"]
            if not isinstance(stored_notes, dict):
                raise ValueError("Invalid review storage schema")
            if any(
                not isinstance(key, str)
                or not STUDY_ID.fullmatch(key)
                or not isinstance(value, str)
                or len(value) > MAX_NOTE_CODE_POINTS
                for key, value in stored_notes.items()
            ):
                raise ValueError("Invalid stored review note")
            notes = dict(stored_notes)
        return dict(votes), notes

    def _save(self, snapshot: ReviewSnapshot) -> None:
        """Flush a same-directory temporary file before atomic replacement."""
        temporary_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=self.path.parent,
                prefix=f".{self.path.name}.",
                suffix=".tmp",
                delete=False,
            ) as handle:
                temporary_path = Path(handle.name)
                json.dump(snapshot, handle, ensure_ascii=True, indent=2,
                          sort_keys=True)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary_path, self.path)
            temporary_path = None
        finally:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)


class ReviewHTTPServer(ThreadingHTTPServer):
    """Serve one design directory with an explicitly located review file."""

    # Absorb reverse-proxy connection bursts during multi-script cold loads.
    request_queue_size: int = 128

    def __init__(
        self,
        server_address: tuple[str, int],
        directory: Path,
        review_file: Path,
        public_origin: str | None = None,
    ) -> None:
        """Configure static files and reviews without writing at startup.

        Args:
            server_address: Bind address and port; zero selects a free port.
            directory: Static design directory to serve.
            review_file: JSON snapshot path, injectable for isolated tests.
            public_origin: Optional HTTPS origin trusted only via loopback.
        """
        self.public_origin = (
            normalize_https_origin(public_origin)
            if public_origin is not None else None
        )
        self.directory = directory.resolve()
        self.reviews = ReviewStore(
            review_file.resolve(),
            self.directory / "big-standby-motion-prototype",
        )
        super().__init__(server_address, ReviewRequestHandler)


class ReviewRequestHandler(SimpleHTTPRequestHandler):
    """Handle bounded same-origin writes alongside standard static requests."""

    server: ReviewHTTPServer

    def __init__(
        self,
        request: socket.socket,
        client_address: tuple[str, int],
        server: ReviewHTTPServer,
    ) -> None:
        """Use the server's static directory for ordinary file routes."""
        super().__init__(request, client_address, server,
                         directory=str(server.directory))

    def end_headers(self) -> None:
        """Prevent stale prototype HTML, scripts, and review responses."""
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_GET(self) -> None:
        """Return persisted reviews or delegate static files to the stdlib."""
        if self.path.partition("?")[0] != API_PATH:
            super().do_GET()
            return
        try:
            snapshot = self.server.reviews.read()
        except (OSError, ValueError, RecursionError) as error:
            LOGGER.error("Could not read review storage (%s)",
                         type(error).__name__)
            self._send_json(500, {"error": "Could not read review storage"})
            return
        self._send_json(200, snapshot)

    def do_POST(self) -> None:
        """Validate a rating or note and acknowledge only a successful save."""
        if self.path.partition("?")[0] != API_PATH:
            self._send_json(404, {"error": "Unknown API route"})
            return
        if not self._same_origin():
            self._send_json(403, {"error": "Cross-origin write forbidden"})
            return
        if (
            len(self.headers.get_all("Content-Type", [])) != 1
            or self.headers.get_content_type() != "application/json"
            or self.headers.get_content_charset() not in (None, "utf-8")
        ):
            self._send_json(415, {"error": "Use application/json with UTF-8"})
            return
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") is not None:
            self._send_json(400, {"error": "Transfer-Encoding is unsupported"})
            return
        if len(lengths) != 1 or not re.fullmatch(r"[0-9]+", lengths[0]):
            self._send_json(400, {"error": "One Content-Length is required"})
            return
        if len(lengths[0]) > 5 or int(lengths[0]) > MAX_REQUEST_BYTES:
            self._send_json(413, {"error": "Review request is too large"})
            return
        length = int(lengths[0])
        try:
            self.connection.settimeout(10)
            body = self.rfile.read(length)
            if len(body) != length:
                raise ValueError("Incomplete request body")
            payload: object = json.loads(
                body.decode("utf-8"), object_pairs_hook=unique_object,
            )
            if not isinstance(payload, dict):
                raise ValueError("Expected JSON object")
            keys = set(payload)
            if keys == {"id", "vote"}:
                study_id, vote = payload["id"], payload["vote"]
                if (
                    not isinstance(study_id, str)
                    or study_id not in self.server.reviews.study_ids
                    or vote not in ("keep", "reject", None)
                ):
                    raise ValueError("Invalid study ID or vote")
                action_kind = "vote"
            elif keys == {"id", "note"}:
                study_id, note = payload["id"], payload["note"]
                if (
                    not isinstance(study_id, str)
                    or study_id not in self.server.reviews.study_ids
                    or not isinstance(note, str)
                    or len(note) > MAX_NOTE_CODE_POINTS
                ):
                    raise ValueError("Invalid study ID or note")
                action_kind = "note"
            else:
                raise ValueError("Expected exactly id and vote or id and note")
        except (ValueError, RecursionError):
            self._send_json(400, {"error": "Invalid review request"})
            return
        except OSError:
            LOGGER.exception("Could not read review request")
            self._send_json(408, {"error": "Could not read request body"})
            return
        try:
            if action_kind == "vote":
                snapshot = self.server.reviews.update(study_id, vote)
            else:
                snapshot = self.server.reviews.update_note(study_id, note)
        except (OSError, ValueError, RecursionError) as error:
            LOGGER.error("Could not save review storage (%s)",
                         type(error).__name__)
            self._send_json(500, {"error": "Could not save review storage"})
            return
        self._send_json(200, snapshot)

    def _same_origin(self) -> bool:
        """Keep HTTP writes; trust configured HTTPS only via loopback."""
        if self.headers.get("Sec-Fetch-Site") not in (
            None, "same-origin", "none",
        ):
            return False
        origins = self.headers.get_all("Origin", [])
        if not origins:
            return True
        hosts = self.headers.get_all("Host", [])
        if len(origins) != 1 or len(hosts) != 1:
            return False
        try:
            origin = urlsplit(origins[0])
            if origin.scheme == "https":
                if (
                    self.server.public_origin is None
                    or self.client_address[0] not in ("127.0.0.1", "::1")
                    or normalize_https_origin(origins[0])
                    != self.server.public_origin
                ):
                    return False
                # The loopback proxy may rewrite Host to the HTTP backend.
                normalize_https_origin(f"https://{hosts[0]}")
                return True
            host = urlsplit(f"http://{hosts[0]}")
            return (
                origin.scheme == "http"
                and origin.hostname is not None
                and origin.hostname == host.hostname
                and (origin.port if origin.port is not None else 80)
                == (host.port if host.port is not None else 80)
                and not (origin.username or origin.password or origin.path
                         or origin.query or origin.fragment or host.username
                         or host.password or host.path or host.query
                         or host.fragment)
            )
        except ValueError:
            return False

    def _send_json(self, status: int, payload: object) -> None:
        """Send a complete, non-cacheable JSON response without CORS grants."""
        content = (json.dumps(payload, ensure_ascii=True) + "\n").encode(
            "utf-8",
        )
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()
        self.wfile.write(content)
        if self.command != "POST" or status < 400:
            return
        # Unread POST bytes can reset Windows sockets and discard the response.
        self.wfile.flush()
        try:
            self.connection.shutdown(socket.SHUT_WR)
            deadline = time.monotonic() + REJECTION_DRAIN_SECONDS
            remaining = MAX_REQUEST_BYTES + 1
            # shortcut: bounded drain; use a real server for hostile uploads.
            while remaining:
                timeout = deadline - time.monotonic()
                if timeout <= 0:
                    LOGGER.warning("Rejected request drain deadline reached")
                    return
                self.connection.settimeout(timeout)
                chunk = self.rfile.read1(remaining)
                if not chunk:
                    return
                remaining -= len(chunk)
        except TimeoutError:
            LOGGER.warning("Rejected request drain timed out")
        except OSError:
            LOGGER.warning("Could not drain rejected request", exc_info=True)


def main() -> None:
    """Launch the LAN prototype; trusted-network use only, without auth."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--public-origin",
        help="HTTPS origin trusted only from a loopback reverse proxy",
    )
    args = parser.parse_args()
    if args.public_origin is not None:
        try:
            normalize_https_origin(args.public_origin)
        except ValueError as error:
            parser.error(f"--public-origin: {error}")
    directory = Path(__file__).resolve().parent
    review_file = directory / "big-standby-motion-prototype/review-votes.json"
    logging.basicConfig(level=logging.INFO)
    with ReviewHTTPServer(
        (args.bind, args.port), directory, review_file,
        public_origin=args.public_origin,
    ) as server:
        LOGGER.warning("Prototype has no auth; use only on a trusted network")
        if args.public_origin is not None:
            LOGGER.info("HTTPS origin %s trusted only via loopback",
                        args.public_origin)
        LOGGER.info("Serving %s on %s:%s", directory, args.bind, args.port)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            LOGGER.info("Prototype server stopped")


if __name__ == "__main__":
    main()
