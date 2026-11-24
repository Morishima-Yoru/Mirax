"""Exercise review persistence through an isolated loopback HTTP server."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import http.client
import io
import json
from pathlib import Path
import socket
import tempfile
import threading
from typing import Final
import unittest
from unittest.mock import Mock, patch

from design import serve_big_standby as prototype


FIRST_ID: Final[str] = "study-41-strain-drift"
SECOND_ID: Final[str] = "study-42-bar-relay"
PUBLIC_ORIGIN: Final[str] = "https://demo.example.ts.net:8443"
STUDY_FILES: Final[tuple[str, ...]] = (
    "41-strain-drift.js", "42-bar-relay.js",
    "99-boundary.js", "100-new-candidate.js", "112-current-candidate.js",
) + tuple(f"{number:02d}-candidate.js" for number in range(43, 49))


class ReviewHTTPTests(unittest.TestCase):
    """Keep every fixture, review, and temporary write outside real votes."""

    def setUp(self) -> None:
        """Start a fresh server on an OS-selected, loopback-only port."""
        self.temporary = tempfile.TemporaryDirectory(
            prefix="standby-reviews-",
        )
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.studies = self.directory / "big-standby-motion-prototype"
        self.studies.mkdir()
        for filename in (*STUDY_FILES, "off.js", "signal.js", "scan.js",
                         "quiet.js", "7-not-numbered.js",
                         "1000-too-long.js", "100-invalid_name.js"):
            (self.studies / filename).write_bytes(b"// Fixture.\n")
        (self.directory / "index.html").write_text("fixture", encoding="utf-8")
        self.review_file = self.studies / "review-votes.json"
        self.start_server()
        self.addCleanup(self.stop_server)

    def start_server(self, public_origin: str | None = None) -> None:
        """Start a fixture listener with an optional explicit proxy origin."""
        self.server = prototype.ReviewHTTPServer(
            ("127.0.0.1", 0), self.directory, self.review_file,
            public_origin=public_origin,
        )
        self.port = self.server.server_port
        self.assertNotEqual(self.port, 8765)
        self.origin = f"http://127.0.0.1:{self.port}"
        self.thread = threading.Thread(
            target=self.server.serve_forever,
            kwargs={"poll_interval": 0.01}, daemon=True,
        )
        self.thread.start()

    def stop_server(self) -> None:
        """Stop request workers before removing the fixture directory."""
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.assertFalse(self.thread.is_alive())

    def request(
        self,
        method: str = "GET",
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        path: str = prototype.API_PATH,
    ) -> tuple[int, dict[str, str], bytes]:
        """Make one real HTTP request and return status, headers, and bytes."""
        connection = http.client.HTTPConnection(
            "127.0.0.1", self.port, timeout=5,
        )
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return (response.status, dict(response.getheaders()),
                    response.read())
        finally:
            connection.close()

    def post(
        self,
        study_id: str = FIRST_ID,
        vote: str | None = "keep",
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        """Submit the exact public rating payload."""
        return self.request(
            "POST", json.dumps({"id": study_id, "vote": vote}).encode("utf-8"),
            {"Content-Type": "application/json", **(headers or {})},
        )

    def post_note(
        self,
        study_id: str = FIRST_ID,
        note: object = "test note",
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        """Submit a candidate note payload."""
        return self.request(
            "POST", json.dumps({"id": study_id, "note": note}).encode("utf-8"),
            {"Content-Type": "application/json", **(headers or {})},
        )

    def test_reverse_proxy_listener_backlog_is_128(self) -> None:
        """Keep cold-load capacity explicit without timing assumptions."""
        self.assertEqual(self.server.request_queue_size, 128)

    def test_missing_store_returns_empty_without_creating_file(self) -> None:
        self.assertFalse(self.review_file.exists())
        status, headers, body = self.request()
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"schemaVersion": 1, "votes": {}})
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertNotIn("Access-Control-Allow-Origin", headers)
        self.assertFalse(self.review_file.exists())

    def test_keep_reject_reset_preserve_other_ids_and_reload(self) -> None:
        self.assertEqual(self.post()[0], 200)
        self.assertEqual(self.post(SECOND_ID, "reject")[0], 200)
        expected = {"schemaVersion": 1, "votes": {
            FIRST_ID: "keep", SECOND_ID: "reject",
        }}
        self.assertEqual(json.loads(self.request()[2]), expected)
        self.assertEqual(
            prototype.ReviewStore(self.review_file, self.studies).read(),
            expected,
        )
        saved = self.review_file.read_text(encoding="utf-8")
        self.assertIn('\n  "schemaVersion": 1,\n', saved)
        self.assertTrue(saved.endswith("\n"))
        self.assertEqual(json.loads(saved), expected)
        self.assertEqual(self.post(FIRST_ID, "reject")[0], 200)
        self.assertEqual(self.post(FIRST_ID, None)[0], 200)
        self.assertEqual(self.post(FIRST_ID, None)[0], 200)
        self.assertEqual(json.loads(self.request()[2]), {
            "schemaVersion": 1, "votes": {SECOND_ID: "reject"},
        })

    def test_three_digit_ids_preserve_legacy_reviews_and_reload(self) -> None:
        """Keep two-digit IDs stable while adding three-digit candidates."""
        expected = {
            FIRST_ID: "keep", "study-99-boundary": "reject",
            "study-100-new-candidate": "keep",
            "study-112-current-candidate": "reject",
        }
        for study_id, vote in expected.items():
            self.assertIn(study_id, self.server.reviews.study_ids)
            self.assertEqual(self.post(study_id, vote)[0], 200)
        reloaded = prototype.ReviewStore(self.review_file, self.studies)
        self.assertEqual(reloaded.read(), {
            "schemaVersion": 1, "votes": expected,
        })
        self.assertEqual(reloaded.study_ids, self.server.reviews.study_ids)
        self.assertEqual(self.post("study-100-new-candidate", None)[0], 200)
        expected.pop("study-100-new-candidate")
        self.assertEqual(json.loads(self.request()[2])["votes"], expected)
        self.assertEqual(self.post("study-1000-too-long")[0], 400)
        self.assertEqual(self.post("study-7-not-numbered")[0], 400)
        self.assertEqual(self.post("study-100-invalid_name")[0], 400)

    def test_retired_three_digit_history_is_preserved(self) -> None:
        """Retain history without restoring retired candidates."""
        expected = {"schemaVersion": 1, "votes": {
            "study-101-retired": "reject", FIRST_ID: "keep",
        }}
        self.review_file.write_text(json.dumps(expected), encoding="utf-8")
        self.assertEqual(json.loads(self.request()[2]), expected)
        self.assertEqual(self.post("study-101-retired")[0], 400)
        self.assertEqual(self.post(SECOND_ID, "keep")[0], 200)
        self.assertEqual(json.loads(self.request()[2])["votes"][
            "study-101-retired"], "reject")

    def test_archived_sources_preserve_history_without_accepting_writes(
        self,
    ) -> None:
        """Discover the new composition, not archived rejected sources."""
        self.stop_server()
        archived_id = "study-174-depth-telemetry"
        composition_id = "study-209-cathode-extrusion"
        archive = self.studies / "archive" / "v29-rejected"
        archive.mkdir(parents=True)
        (archive / "174-depth-telemetry.js").write_bytes(b"// Archived.")
        (self.studies / "209-cathode-extrusion.js").write_bytes(
            b"// New composition.",
        )
        expected = {
            "schemaVersion": 1, "votes": {archived_id: "reject"},
            "notes": {archived_id: "previous review"},
        }
        self.review_file.write_text(json.dumps(expected), encoding="utf-8")
        self.start_server()
        self.assertIn(composition_id, self.server.reviews.study_ids)
        self.assertNotIn(archived_id, self.server.reviews.study_ids)
        self.assertEqual(json.loads(self.request()[2]), expected)
        self.assertEqual(self.post(composition_id, "keep")[0], 200)
        expected["votes"][composition_id] = "keep"
        self.assertEqual(json.loads(self.request()[2]), expected)
        saved = self.review_file.read_bytes()
        self.assertEqual(self.post(archived_id, None)[0], 400)
        self.assertEqual(self.post_note(archived_id, "")[0], 400)
        self.assertEqual(self.review_file.read_bytes(), saved)

    def test_preview_html_and_scripts_are_not_cacheable(self) -> None:
        """Keep HTML and script responses out of browser caches."""
        for method in ("GET", "HEAD"):
            for path in (
                "/index.html?variant=C&v=10",
                "/big-standby-motion-prototype/41-strain-drift.js?v=10",
            ):
                with self.subTest(method=method, path=path):
                    status, headers, _ = self.request(method, path=path)
                    self.assertEqual(status, 200)
                    self.assertEqual(headers.get("Cache-Control"), "no-store")
        self.assertFalse(self.review_file.exists())

    def test_static_routes_still_work(self) -> None:
        self.assertEqual(self.request(path="/index.html")[2], b"fixture")
        status, headers, body = self.request(
            path="/big-standby-motion-prototype/41-strain-drift.js",
        )
        self.assertEqual(status, 200)
        self.assertIn("javascript", headers["Content-type"])
        self.assertEqual(body, b"// Fixture.\n")
        self.assertEqual(self.request("HEAD", path="/index.html")[0], 200)
        self.assertEqual(self.request(path="/missing")[0], 404)
        self.assertEqual(self.request("POST", path="/elsewhere")[0], 404)

    def test_invalid_ids_votes_and_payloads_do_not_create_store(self) -> None:
        payloads: tuple[object, ...] = (
            {}, [], None, "text", {"id": FIRST_ID}, {"vote": "keep"},
            {"id": FIRST_ID, "vote": "keep", "extra": True},
            {"id": FIRST_ID, "vote": "KEEP"},
            {"id": FIRST_ID, "vote": 1},
            {"id": FIRST_ID, "vote": []},
            {"id": FIRST_ID, "vote": {}},
            {"id": 41, "vote": "keep"},
            {"id": [], "vote": "keep"},
            {"id": "study-99-unknown", "vote": "keep"},
            *({"id": legacy, "vote": "keep"}
              for legacy in ("off", "signal", "scan", "quiet")),
        )
        for payload in payloads:
            with self.subTest(payload=payload):
                status = self.request(
                    "POST", json.dumps(payload).encode("utf-8"),
                    {"Content-Type": "application/json"},
                )[0]
                self.assertEqual(status, 400)
        for body in (
            b"", b"{", b"\xff", b'{"id": 1, "id": 2, "vote": null}',
            b"[" * prototype.MAX_REQUEST_BYTES,
        ):
            with self.subTest(body=body):
                self.assertEqual(self.request(
                    "POST", body, {"Content-Type": "application/json"},
                )[0], 400)
        self.assertFalse(self.review_file.exists())

    def test_content_type_length_and_size_are_bounded(self) -> None:
        for content_type in ("text/plain", "application/x-www-form-urlencoded",
                             "application/json; charset=iso-8859-1"):
            with self.subTest(content_type=content_type):
                self.assertEqual(self.post(headers={
                    "Content-Type": content_type,
                })[0], 415)
        self.assertEqual(self.request("POST", b"{}")[0], 415)
        self.assertEqual(self.request(
            "POST", b" " * (prototype.MAX_REQUEST_BYTES + 1),
            {"Content-Type": "application/json"},
        )[0], 413)
        for headers, status in (
            ({"Content-Length": "-1"}, 400),
            ({"Content-Length": "invalid"}, 400),
            ({"Content-Length": "9" * 5000}, 413),
            ({"Transfer-Encoding": "chunked"}, 400),
        ):
            with self.subTest(headers=headers):
                self.assertEqual(self.post(headers=headers)[0], status)
        self.assertFalse(self.review_file.exists())

    def test_missing_duplicate_lengths_and_truncated_body(self) -> None:
        requests = (
            b"Content-Type: application/json\r\n\r\n{}",
            b"Content-Type: application/json\r\nContent-Length: 2\r\n"
            b"Content-Length: 2\r\n\r\n{}",
            b"Content-Type: application/json\r\nContent-Type: text/plain\r\n"
            b"Content-Length: 2\r\n\r\n{}",
            b"Content-Type: application/json\r\nContent-Length: 20\r\n\r\n{}",
        )
        for headers_and_body in requests:
            with self.subTest(request=headers_and_body):
                with socket.create_connection(("127.0.0.1", self.port),
                                              timeout=5) as connection:
                    connection.sendall(
                        b"POST /api/d-reviews HTTP/1.0\r\n"
                        b"Host: localhost\r\n" + headers_and_body,
                    )
                    connection.shutdown(socket.SHUT_WR)
                    response = http.client.HTTPResponse(connection)
                    response.begin()
                    self.assertIn(response.status, (400, 415))
                    response.read()
        self.assertFalse(self.review_file.exists())

    def test_cross_origin_writes_are_rejected(self) -> None:
        for headers in (
            {"Origin": "https://evil.example"},
            {"Origin": "null"},
            {"Origin": f"http://127.0.0.1:{self.port + 1}"},
            {"Origin": self.origin.replace("http:", "https:")},
            {"Origin": self.origin + "/path"},
            {"Origin": "http://[invalid"},
            {"Sec-Fetch-Site": "cross-site"},
            {"Sec-Fetch-Site": "same-site"},
        ):
            with self.subTest(headers=headers):
                self.assertEqual(self.post(headers=headers)[0], 403)
        self.assertFalse(self.review_file.exists())
        self.assertEqual(self.post(headers={
            "Origin": self.origin, "Sec-Fetch-Site": "same-origin",
        })[0], 200)
        self.assertEqual(self.post(SECOND_ID, "reject", {
            "Host": "mirax.tailnet.example:8766",
            "Origin": "http://mirax.tailnet.example:8766",
        })[0], 200)
        self.assertEqual(self.post(headers={
            "Host": "LAN.EXAMPLE:80", "Origin": "http://lan.example",
        })[0], 200)

    def test_configured_https_proxy_writes_and_clears(self) -> None:
        """Allow the configured origin despite a rewritten backend Host."""
        self.stop_server()
        self.start_server(PUBLIC_ORIGIN)
        headers = {"Origin": PUBLIC_ORIGIN, "Sec-Fetch-Site": "same-origin"}
        self.assertEqual(self.post(headers=headers)[0], 200)
        self.assertEqual(self.post_note(note="HTTPS note", headers=headers)[0],
                         200)
        snapshot = json.loads(self.request()[2])
        self.assertEqual(snapshot["votes"], {FIRST_ID: "keep"})
        self.assertEqual(snapshot["notes"], {FIRST_ID: "HTTPS note"})
        self.assertEqual(self.post(vote=None, headers=headers)[0], 200)
        self.assertEqual(self.post_note(note="", headers=headers)[0], 200)
        self.assertEqual(json.loads(self.request()[2]), {
            "schemaVersion": 1, "votes": {},
        })
        self.assertEqual(self.post(headers={"Origin": self.origin})[0], 200)
        self.assertEqual(self.post_note(headers={"Origin": self.origin})[0],
                         200)

    def test_https_origin_normalizes_host_and_default_port(self) -> None:
        """Compare equivalent hosts and implicit or explicit HTTPS ports."""
        for configured, origin in (
            ("HTTPS://DEMO.EXAMPLE.TS.NET", "https://demo.example.ts.net:443"),
            ("https://demo.example.ts.net:443", "https://DEMO.EXAMPLE.TS.NET"),
            ("https://[0:0:0:0:0:0:0:1]", "https://[::1]:443"),
        ):
            with self.subTest(configured=configured, origin=origin):
                self.stop_server()
                self.start_server(configured)
                self.assertEqual(self.post(headers={"Origin": origin})[0], 200)
                self.assertEqual(self.post_note(headers={"Origin": origin})[0],
                                 200)

    def test_configured_https_denials_preserve_bytes(self) -> None:
        """Deny wrong origins, invalid authorities and fetch-site bypasses."""
        self.stop_server()
        self.start_server(PUBLIC_ORIGIN)
        self.assertEqual(self.post()[0], 200)
        self.assertEqual(self.post_note()[0], 200)
        original = self.review_file.read_bytes()
        bad_origins = (
            "null", "https://other.example.ts.net:8443",
            "https://demo.example.ts.net:8444", "https://demo.example.ts.net",
            "http://demo.example.ts.net:8443",
            f"{PUBLIC_ORIGIN} {PUBLIC_ORIGIN}",
            f"{PUBLIC_ORIGIN},{PUBLIC_ORIGIN}",
            "https://user:password@demo.example.ts.net:8443",
            "https://@demo.example.ts.net:8443",
            f"{PUBLIC_ORIGIN}/", f"{PUBLIC_ORIGIN}/path",
            f"{PUBLIC_ORIGIN}?query", f"{PUBLIC_ORIGIN}?",
            f"{PUBLIC_ORIGIN}#fragment", f"{PUBLIC_ORIGIN}#",
            "https://demo.example.ts.net:", "https://demo.example.ts.net:bad",
            "https://demo.example.ts.net:65536", "https://[invalid",
            fr"{PUBLIC_ORIGIN}\path",
        )
        bad_headers = tuple({"Origin": origin} for origin in bad_origins) + (
            {"Origin": PUBLIC_ORIGIN, "Sec-Fetch-Site": "cross-site"},
            {"Origin": PUBLIC_ORIGIN, "Sec-Fetch-Site": "same-site"},
            {"Origin": "https://evil.example", "X-Forwarded-Proto": "https",
             "X-Forwarded-Host": "demo.example.ts.net:8443",
             "Forwarded": ('for=127.0.0.1;proto=https;'
                           'host="demo.example.ts.net:8443"')},
        ) + tuple({"Origin": PUBLIC_ORIGIN, "Host": host} for host in (
            "", "user@localhost", "@localhost", "localhost/path",
            "localhost?query", "localhost#fragment", "localhost:",
            "localhost:bad", "localhost:65536", "localhost other",
            "localhost,other", "http://localhost", "[::1",
        ))
        for headers in bad_headers:
            with self.subTest(headers=headers):
                self.assertEqual(self.post(vote=None, headers=headers)[0], 403)
                self.assertEqual(self.post_note(note="", headers=headers)[0],
                                 403)
                self.assertEqual(self.review_file.read_bytes(), original)

    def test_https_proxy_requires_single_origin_and_host(self) -> None:
        """Require one Host header and one Origin header."""
        self.stop_server()
        self.start_server(PUBLIC_ORIGIN)
        self.assertEqual(self.post_note()[0], 200)
        original = self.review_file.read_bytes()
        body = json.dumps({"id": FIRST_ID, "note": ""}).encode("utf-8")
        for headers in (
            f"Origin: {PUBLIC_ORIGIN}\r\n",
            "Host: localhost\r\nHost: localhost\r\n"
            f"Origin: {PUBLIC_ORIGIN}\r\n",
            f"Host: localhost\r\nOrigin: {PUBLIC_ORIGIN}\r\n"
            f"Origin: {PUBLIC_ORIGIN}\r\n",
        ):
            with self.subTest(headers=headers):
                with socket.create_connection(("127.0.0.1", self.port),
                                              timeout=5) as connection:
                    connection.sendall((
                        "POST /api/d-reviews HTTP/1.0\r\n" + headers
                        + "Content-Type: application/json\r\n"
                        + f"Content-Length: {len(body)}\r\n\r\n"
                    ).encode("ascii") + body)
                    response = http.client.HTTPResponse(connection)
                    response.begin()
                    self.assertEqual(response.status, 403)
                    response.read()
                self.assertEqual(self.review_file.read_bytes(), original)

    def test_https_proxy_requires_loopback_peer(self) -> None:
        """Inject peers into real POST handling, ignoring forwarded IPs."""
        self.stop_server()
        self.start_server(PUBLIC_ORIGIN)
        self.assertEqual(self.post()[0], 200)
        self.assertEqual(self.post_note()[0], 200)
        original = self.review_file.read_bytes()
        headers = {"Origin": PUBLIC_ORIGIN, "X-Forwarded-For": "127.0.0.1",
                   "Forwarded": "for=127.0.0.1;proto=https"}
        for peer, status in (
            ("127.0.0.1", 200), ("::1", 200), ("203.0.113.10", 403),
            ("100.64.0.10", 403), ("127.0.0.2", 403),
            ("::ffff:127.0.0.1", 403), ("localhost", 403),
        ):
            def serve_with_peer(
                request: socket.socket,
                client_address: tuple[str, int],
                server: prototype.ReviewHTTPServer,
            ) -> None:
                """Replace only the peer observed by the real handler."""
                prototype.ReviewRequestHandler(
                    request, (peer, client_address[1]), server,
                )

            with self.subTest(peer=peer), patch.object(
                self.server, "RequestHandlerClass", serve_with_peer,
            ):
                self.assertEqual(self.post(headers=headers)[0], status)
                self.assertEqual(self.post_note(headers=headers)[0], status)
                self.assertEqual(self.review_file.read_bytes(), original)

    def test_unconfigured_https_ignores_forwarded_headers(self) -> None:
        """Never infer HTTPS permission from Host or forwarded headers."""
        self.assertEqual(self.post()[0], 200)
        self.assertEqual(self.post_note()[0], 200)
        original = self.review_file.read_bytes()
        for headers in (
            {"Origin": PUBLIC_ORIGIN},
            {"Origin": PUBLIC_ORIGIN, "Host": "demo.example.ts.net:8443",
             "X-Forwarded-Proto": "https",
             "X-Forwarded-Host": "demo.example.ts.net:8443"},
            {"Origin": "http://evil.example", "X-Forwarded-Proto": "http",
             "X-Forwarded-Host": "evil.example"},
        ):
            with self.subTest(headers=headers):
                self.assertEqual(self.post(vote=None, headers=headers)[0], 403)
                self.assertEqual(self.post_note(note="", headers=headers)[0],
                                 403)
                self.assertEqual(self.review_file.read_bytes(), original)

    def test_invalid_public_origins_fail_before_binding(self) -> None:
        """Reject invalid config before listening or writing reviews."""
        self.assertEqual(self.post()[0], 200)
        original = self.review_file.read_bytes()
        with patch.object(prototype.ThreadingHTTPServer, "__init__") as bind:
            for origin in (
                "", "null", "http://demo.example.ts.net:8443",
                f"{PUBLIC_ORIGIN} {PUBLIC_ORIGIN}",
                f"{PUBLIC_ORIGIN},{PUBLIC_ORIGIN}",
                "https://user:password@demo.example.ts.net:8443",
                "https://@demo.example.ts.net:8443",
                f"{PUBLIC_ORIGIN}/", f"{PUBLIC_ORIGIN}/path",
                f"{PUBLIC_ORIGIN}?query", f"{PUBLIC_ORIGIN}?",
                f"{PUBLIC_ORIGIN}#fragment", f"{PUBLIC_ORIGIN}#",
                "https://demo.example.ts.net:",
                "https://demo.example.ts.net:bad",
                "https://demo.example.ts.net:65536",
                "https://demo.example.ts.net:-1", "https://[invalid",
                f" {PUBLIC_ORIGIN}", f"{PUBLIC_ORIGIN}\n",
                "https://demo.\texample.ts.net:8443",
                "https://-invalid.example:8443", "https://demo..example:8443",
            ):
                with self.subTest(origin=origin), self.assertRaisesRegex(
                    ValueError, "HTTPS origin",
                ):
                    prototype.ReviewHTTPServer(
                        ("127.0.0.1", 0), self.directory, self.review_file,
                        public_origin=origin,
                    )
            bind.assert_not_called()
        self.assertEqual(self.review_file.read_bytes(), original)

    def test_public_origin_cli_validation_and_forwarding(self) -> None:
        """Fail invalid CLI config clearly; forward optional valid config."""
        with (
            patch("sys.argv", [
                "serve_big_standby.py", "--public-origin", "null",
            ]),
            patch("sys.stderr", new_callable=io.StringIO) as stderr,
            patch.object(prototype, "ReviewHTTPServer") as constructor,
        ):
            with self.assertRaises(SystemExit) as result:
                prototype.main()
            self.assertEqual(result.exception.code, 2)
            self.assertIn("--public-origin: Expected one HTTPS origin",
                          stderr.getvalue())
            constructor.assert_not_called()
        for origin in (None, PUBLIC_ORIGIN):
            argv = ["serve_big_standby.py"]
            if origin is not None:
                argv.extend(["--public-origin", origin])
            with (
                self.subTest(origin=origin), patch("sys.argv", argv),
                patch.object(prototype.logging, "basicConfig"),
                patch.object(prototype, "ReviewHTTPServer") as constructor,
            ):
                server = constructor.return_value.__enter__.return_value
                server.serve_forever.side_effect = KeyboardInterrupt
                prototype.main()
                directory = Path(prototype.__file__).resolve().parent
                constructor.assert_called_once_with(
                    ("0.0.0.0", 8765), directory,
                    directory / (
                        "big-standby-motion-prototype/review-votes.json"
                    ),
                    public_origin=origin,
                )

    def test_error_json_drains_bounded_input_before_returning(self) -> None:
        """Check the teardown invariant independently of TCP race timing."""
        handler = object.__new__(prototype.ReviewRequestHandler)
        handler.command = "POST"
        handler.request_version = "HTTP/1.1"
        handler.requestline = "POST /api/d-reviews HTTP/1.1"
        handler.client_address = ("127.0.0.1", 0)
        handler.rfile = io.BytesIO(b"x" * (prototype.MAX_REQUEST_BYTES + 2))
        handler.wfile = io.BytesIO()
        handler.connection = Mock(spec=socket.socket)
        handler._send_json(403, {"error": "denied"})
        self.assertEqual(handler.rfile.tell(), prototype.MAX_REQUEST_BYTES + 1)
        handler.connection.shutdown.assert_called_once_with(socket.SHUT_WR)
        self.assertTrue(handler.close_connection)

    def test_repeated_denied_posts_return_complete_json(self) -> None:
        """Exercise every early rejection without retries or ignored resets."""
        body = json.dumps({"id": FIRST_ID, "vote": "keep"}).encode("utf-8")
        cases = (
            (prototype.API_PATH, {"Sec-Fetch-Site": "cross-site"}, 403, body),
            (prototype.API_PATH, {"Origin": "http://evil.example"}, 403, body),
            (prototype.API_PATH, {"Content-Type": "text/plain"}, 415, body),
            ("/elsewhere", {}, 404, body),
            (prototype.API_PATH, {"Content-Length": "-1"}, 400, body),
            (prototype.API_PATH, {"Transfer-Encoding": "chunked"}, 400, body),
            (prototype.API_PATH, {"Content-Length": "9" * 5000}, 413, body),
            (prototype.API_PATH, {}, 413,
             b"x" * (prototype.MAX_REQUEST_BYTES + 1)),
        )
        for iteration in range(40):
            for path, extra_headers, expected_status, request_body in cases:
                with self.subTest(iteration=iteration, path=path,
                                  expected_status=expected_status):
                    status, headers, content = self.request(
                        "POST", request_body,
                        {"Content-Type": "application/json", **extra_headers},
                        path,
                    )
                    self.assertEqual(status, expected_status)
                    self.assertEqual(int(headers["Content-Length"]),
                                     len(content))
                    self.assertEqual(headers["Content-Type"].split(";")[0],
                                     "application/json")
                    self.assertIsInstance(json.loads(content)["error"], str)
        self.assertFalse(self.review_file.exists())
        self.assertEqual(self.request()[0], 200)

    def test_corrupt_storage_is_reported_and_never_overwritten(self) -> None:
        invalid_files = (
            b"{broken", b"\xff", b"[]", b"null", b"[" * 2048,
            b'{"schemaVersion": 2, "votes": {}}',
            b'{"schemaVersion": true, "votes": {}}',
            b'{"schemaVersion": 1.0, "votes": {}}',
            b'{"schemaVersion": 1, "votes": []}',
            b'{"schemaVersion": 1, "votes": {}, "extra": 1}',
            b'{"schemaVersion": 1, "votes": {"off": "keep"}}',
            b'{"schemaVersion": 1, "votes": {"study-41-strain-drift": null}}',
            b'{"schemaVersion": 1, "votes": {}, "votes": {}}',
        )
        for original in invalid_files:
            with self.subTest(original=original):
                self.review_file.write_bytes(original)
                with self.assertLogs(prototype.LOGGER, level="ERROR"):
                    self.assertEqual(self.request()[0], 500)
                    self.assertEqual(self.post()[0], 500)
                self.assertEqual(self.review_file.read_bytes(), original)

    def test_failed_flush_or_replace_never_reports_success(self) -> None:
        self.assertEqual(self.post()[0], 200)
        original = self.review_file.read_bytes()
        for operation in ("fsync", "replace"):
            with self.subTest(operation=operation):
                with (
                    patch.object(prototype.os, operation,
                                 side_effect=OSError("Disk failure")),
                    self.assertLogs(prototype.LOGGER, level="ERROR"),
                ):
                    self.assertEqual(self.post(SECOND_ID, "reject")[0], 500)
                self.assertEqual(self.review_file.read_bytes(), original)
                self.assertEqual(list(self.studies.glob("*.tmp")), [])
        self.assertEqual(json.loads(self.request()[2])["votes"], {
            FIRST_ID: "keep",
        })

    def test_io_read_failure_never_creates_replacement(self) -> None:
        self.review_file.mkdir()
        with self.assertLogs(prototype.LOGGER, level="ERROR"):
            self.assertEqual(self.request()[0], 500)
            self.assertEqual(self.post()[0], 500)
        self.assertTrue(self.review_file.is_dir())
        self.assertEqual(list(self.studies.glob("*.tmp")), [])

    def test_atomic_replace_is_same_directory_and_after_flush(self) -> None:
        self.assertEqual(self.post()[0], 200)
        old_content = self.review_file.read_bytes()
        original_replace = prototype.os.replace
        original_fsync = prototype.os.fsync
        flushed = False

        def observe_fsync(descriptor: int) -> None:
            nonlocal flushed
            original_fsync(descriptor)
            flushed = True

        def observe_replace(source: Path, destination: Path) -> None:
            self.assertTrue(flushed)
            self.assertEqual(source.parent, self.review_file.parent)
            self.assertEqual(destination, self.review_file)
            self.assertEqual(self.review_file.read_bytes(), old_content)
            self.assertEqual(json.loads(source.read_text(encoding="utf-8"))[
                "votes"
            ], {FIRST_ID: "keep", SECOND_ID: "reject"})
            original_replace(source, destination)

        with patch.object(prototype.os, "fsync", side_effect=observe_fsync):
            with patch.object(prototype.os, "replace",
                              side_effect=observe_replace):
                self.assertEqual(self.post(SECOND_ID, "reject")[0], 200)
        self.assertEqual(list(self.studies.glob("*.tmp")), [])

    def test_concurrent_http_updates_preserve_every_id(self) -> None:
        study_ids = tuple(f"study-{Path(name).stem}" for name in STUDY_FILES)
        with ThreadPoolExecutor(max_workers=8) as executor:
            responses = list(executor.map(self.post, study_ids))
        self.assertTrue(all(status == 200 for status, _, _ in responses))
        self.assertEqual(json.loads(self.request()[2])["votes"], {
            study_id: "keep" for study_id in study_ids
        })
        self.assertEqual(list(self.studies.glob("*.tmp")), [])

    def test_unavailable_study_votes_are_preserved(self) -> None:
        self.review_file.write_text(json.dumps({
            "schemaVersion": 1, "votes": {"study-00-retired": "reject"},
        }), encoding="utf-8")
        status, _, body = self.post()
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["votes"], {
            FIRST_ID: "keep", "study-00-retired": "reject",
        })
        self.assertEqual(self.post("study-00-retired", None)[0], 400)

    def test_legacy_unchanged_and_no_file_on_get(self) -> None:
        """Keep legacy response format and avoid file creation on GET."""
        self.assertFalse(self.review_file.exists())
        status, _, body = self.request()
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {
            "schemaVersion": 1, "votes": {},
        })
        self.assertFalse(self.review_file.exists())
        legacy = {"schemaVersion": 1, "votes": {FIRST_ID: "keep"}}
        self.review_file.write_text(json.dumps(legacy), encoding="utf-8")
        original = self.review_file.read_bytes()
        status, _, body = self.request()
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), legacy)
        reloaded = prototype.ReviewStore(self.review_file, self.studies).read()
        self.assertEqual(reloaded, legacy)
        self.assertEqual(self.review_file.read_bytes(), original)

    def test_unicode_multiline_note_reload_and_empty_deletion(self) -> None:
        """Preserve full Unicode notes and remove notes on empty string."""
        self.assertEqual(self.post(FIRST_ID, "keep")[0], 200)
        unicode_note = "Line 1\n  縮排 \r\n中文字符 🎉 💡	\nLine 3"
        status, _, body = self.post_note(FIRST_ID, unicode_note)
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertEqual(payload["votes"], {FIRST_ID: "keep"})
        self.assertEqual(payload["notes"], {FIRST_ID: unicode_note})
        saved = json.loads(self.review_file.read_text(encoding="utf-8"))
        self.assertEqual(saved["votes"], {FIRST_ID: "keep"})
        self.assertEqual(saved["notes"], {FIRST_ID: unicode_note})
        reloaded = prototype.ReviewStore(self.review_file, self.studies).read()
        self.assertEqual(reloaded["notes"][FIRST_ID], unicode_note)
        status, _, body = self.post_note(FIRST_ID, "")
        self.assertEqual(status, 200)
        cleared_payload = json.loads(body)
        self.assertEqual(cleared_payload, {
            "schemaVersion": 1, "votes": {FIRST_ID: "keep"},
        })
        self.assertNotIn("notes", cleared_payload)
        saved_cleared = json.loads(
            self.review_file.read_text(encoding="utf-8")
        )
        self.assertNotIn("notes", saved_cleared)
        reloaded_cleared = prototype.ReviewStore(
            self.review_file, self.studies
        ).read()
        self.assertNotIn("notes", reloaded_cleared)
        self.assertEqual(reloaded_cleared["votes"], {FIRST_ID: "keep"})

    def test_vote_updates_preserve_notes_and_retired_history(self) -> None:
        """Retain notes and retired candidate history across updates."""
        history = {
            "schemaVersion": 1,
            "votes": {"study-00-retired": "reject", FIRST_ID: "keep"},
            "notes": {
                "study-00-retired": "Historical comment",
                FIRST_ID: "Active comment",
            },
        }
        self.review_file.write_text(json.dumps(history), encoding="utf-8")
        status, _, body = self.post(SECOND_ID, "keep")
        self.assertEqual(status, 200)
        payload = json.loads(body)
        self.assertEqual(payload["votes"]["study-00-retired"], "reject")
        self.assertEqual(payload["votes"][FIRST_ID], "keep")
        self.assertEqual(payload["votes"][SECOND_ID], "keep")
        self.assertEqual(payload["notes"]["study-00-retired"],
                         "Historical comment")
        self.assertEqual(payload["notes"][FIRST_ID], "Active comment")
        status, _, body = self.post_note(SECOND_ID, "Second comment")
        self.assertEqual(status, 200)
        note_payload = json.loads(body)
        self.assertEqual(note_payload["votes"], payload["votes"])
        self.assertEqual(note_payload["notes"][SECOND_ID], "Second comment")
        self.assertEqual(note_payload["notes"]["study-00-retired"],
                         "Historical comment")
        self.assertEqual(self.post("study-00-retired", "keep")[0], 400)
        self.assertEqual(self.post_note("study-00-retired", "New")[0], 400)
        self.assertEqual(self.post_note("study-00-retired", "")[0], 400)
        status, _, body = self.post_note(FIRST_ID, "")
        self.assertEqual(status, 200)
        expected_notes = {
            "study-00-retired": "Historical comment",
            SECOND_ID: "Second comment",
        }
        self.assertEqual(json.loads(body)["notes"], expected_notes)
        self.assertEqual(json.loads(body)["votes"], payload["votes"])
        on_disk = json.loads(self.review_file.read_text(encoding="utf-8"))
        self.assertEqual(on_disk["notes"], expected_notes)

    def test_reject_invalid_note_shapes_ids_and_malformed_storage(
        self,
    ) -> None:
        """Reject invalid note request payloads and corrupt stored notes."""
        invalid_payloads = (
            {"id": FIRST_ID, "note": None},
            {"id": FIRST_ID, "note": 123},
            {"id": FIRST_ID, "note": ["text"]},
            {"id": FIRST_ID, "note": {"k": "v"}},
            {"id": FIRST_ID, "note": True},
            {"id": FIRST_ID, "note": "x" * 2001},
            {"id": "study-99-unknown", "note": "test"},
            {"id": "study-00-retired", "note": "test"},
            {"id": "not-study-id", "note": "test"},
            {"id": FIRST_ID, "note": "ok", "extra": 1},
            {"id": FIRST_ID, "vote": "keep", "note": "both"},
        )
        for payload in invalid_payloads:
            with self.subTest(payload=payload):
                status, _, body = self.request(
                    "POST", json.dumps(payload).encode("utf-8"),
                    {"Content-Type": "application/json"},
                )
                self.assertEqual(status, 400)
                self.assertIsInstance(json.loads(body)["error"], str)
        duplicate_key_body = (
            b'{"id": "study-41-strain-drift", "note": "a", "note": "b"}'
        )
        status, _, body = self.request(
            "POST", duplicate_key_body, {"Content-Type": "application/json"},
        )
        self.assertEqual(status, 400)
        self.assertFalse(self.review_file.exists())
        invalid_storage = (
            b'{"schemaVersion": 1, "votes": {}, "notes": null}',
            b'{"schemaVersion": 1, "votes": {}, "notes": []}',
            b'{"schemaVersion": 1, "votes": {}, "notes": "str"}',
            b'{"schemaVersion": 1, "votes": {}, "notes": {"bad": "val"}}',
            b'{"schemaVersion": 1, "votes": {}, "notes": '
            b'{"study-41-strain-drift": 1}}',
            b'{"schemaVersion": 1, "votes": {}, "notes": '
            b'{"study-41-strain-drift": null}}',
            b'{"schemaVersion": 1, "votes": {}, "notes": {'
            b'"study-41-strain-drift": "' + (b"x" * 2001) + b'"}}',
            b'{"schemaVersion": 1, "votes": {}, "notes": {}, "notes": {}}',
            b'{"schemaVersion": 1, "votes": {}, "notes": '
            b'{"study-41-strain-drift": "a", "study-41-strain-drift": "b"}}',
            b'{"schemaVersion": 1, "notes": {}}',
            b'{"votes": {}, "notes": {}}',
            b'{"schemaVersion": 1, "votes": {}, "notes": {}, "extra": 1}',
        )
        for original in invalid_storage:
            with self.subTest(original=original):
                self.review_file.write_bytes(original)
                with self.assertLogs(prototype.LOGGER, level="ERROR"):
                    self.assertEqual(self.request()[0], 500)
                    self.assertEqual(self.post()[0], 500)
                    self.assertEqual(self.post_note()[0], 500)
                self.assertEqual(self.review_file.read_bytes(), original)

    def test_threaded_interleaving_note_and_vote_preserves_both(self) -> None:
        """Concurrently submit votes and notes and preserve all entries."""
        study_ids = tuple(f"study-{Path(name).stem}" for name in STUDY_FILES)
        tasks: list[tuple[str, str, str]] = []
        for index, study_id in enumerate(study_ids):
            vote_val = "keep" if index % 2 == 0 else "reject"
            tasks.append(("vote", study_id, vote_val))
            tasks.append(("note", study_id, f"Note for {study_id}"))

        def run_task(task: tuple[str, str, str]) -> int:
            kind, study_id, val = task
            if kind == "vote":
                return self.post(study_id, val)[0]
            return self.post_note(study_id, val)[0]

        with ThreadPoolExecutor(max_workers=8) as executor:
            statuses = list(executor.map(run_task, tasks))
        self.assertTrue(all(status == 200 for status in statuses))
        snapshot = json.loads(self.request()[2])
        self.assertEqual(len(snapshot["votes"]), len(study_ids))
        self.assertEqual(len(snapshot["notes"]), len(study_ids))
        for index, study_id in enumerate(study_ids):
            expected_vote = "keep" if index % 2 == 0 else "reject"
            expected_note = f"Note for {study_id}"
            self.assertEqual(snapshot["votes"][study_id], expected_vote)
            self.assertEqual(snapshot["notes"][study_id], expected_note)

    def test_note_failed_flush_or_replace_never_reports_success(self) -> None:
        """Disk failure on note write reports 500 and keeps previous bytes."""
        self.assertEqual(self.post_note(FIRST_ID, "Initial note")[0], 200)
        original = self.review_file.read_bytes()
        for operation in ("fsync", "replace"):
            with self.subTest(operation=operation):
                with (
                    patch.object(prototype.os, operation,
                                 side_effect=OSError("Disk failure")),
                    self.assertLogs(prototype.LOGGER, level="ERROR"),
                ):
                    self.assertEqual(
                        self.post_note(SECOND_ID, "Fail note")[0], 500
                    )
                self.assertEqual(self.review_file.read_bytes(), original)
                self.assertEqual(list(self.studies.glob("*.tmp")), [])
        self.assertEqual(json.loads(self.request()[2])["notes"], {
            FIRST_ID: "Initial note",
        })

    def test_note_origin_and_body_limits(self) -> None:
        """Enforce CORS origin check and 16384-byte request limits on notes."""
        self.assertEqual(
            self.post_note(headers={"Origin": "https://evil.example"})[0], 403
        )
        self.assertEqual(
            self.post_note(headers={"Content-Type": "text/plain"})[0], 415
        )
        self.assertEqual(
            self.request("POST", b"x" * (prototype.MAX_REQUEST_BYTES + 1),
                         {"Content-Type": "application/json"})[0], 413
        )
        self.assertEqual(
            self.post_note(headers={"Content-Length": "100000"})[0], 413
        )
        self.assertFalse(self.review_file.exists())
        note_2000 = "字" * 2000
        status, _, body = self.post_note(FIRST_ID, note_2000)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["notes"][FIRST_ID], note_2000)
        self.assertEqual(self.post_note(FIRST_ID, "字" * 2001)[0], 400)
        headers = {"Content-Type": "application/json", "Origin": self.origin}
        emoji_note = "🎉" * 2000
        raw_body = json.dumps({"id": FIRST_ID, "note": emoji_note},
                              ensure_ascii=False).encode("utf-8")
        raw_body += b" " * (prototype.MAX_REQUEST_BYTES - len(raw_body))
        self.assertEqual(len(raw_body), 16384)
        status, _, body = self.request("POST", raw_body, headers)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["notes"][FIRST_ID], emoji_note)
        original = self.review_file.read_bytes()
        self.assertEqual(
            self.request("POST", raw_body + b" ", headers)[0], 413,
        )
        self.assertEqual(self.post_note(FIRST_ID, emoji_note)[0], 413)
        self.assertEqual(self.review_file.read_bytes(), original)
        oversized_note = json.dumps({"id": FIRST_ID, "note": "🎉" * 2001},
                                    ensure_ascii=False).encode("utf-8")
        self.assertEqual(self.request("POST", oversized_note, headers)[0], 400)
        self.assertEqual(self.review_file.read_bytes(), original)

    def test_whitespace_and_escaped_code_points_round_trip(self) -> None:
        """Do not trim notes or lose JSON-escaped code points."""
        for note in (" \t\r\n ", "\x00<markup>\ud800x\udfff"):
            with self.subTest(note=repr(note)):
                status, _, body = self.post_note(FIRST_ID, note)
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body)["notes"], {FIRST_ID: note})
                self.assertEqual(
                    prototype.ReviewStore(self.review_file,
                                          self.studies).read()["notes"],
                    {FIRST_ID: note},
                )
        self.assertEqual(self.post_note(FIRST_ID, "")[0], 200)
        self.assertEqual(json.loads(self.request()[2]), {
            "schemaVersion": 1, "votes": {},
        })

    def test_error_responses_and_logs_do_not_expose_notes(self) -> None:
        """Keep private note text out of exception messages and HTTP errors."""
        private_note = "private note marker"
        malformed = json.dumps({"id": FIRST_ID, "note": private_note}) + "!"
        status, _, body = self.request(
            "POST", malformed.encode("utf-8"),
            {"Content-Type": "application/json"},
        )
        self.assertEqual(status, 400)
        self.assertNotIn(private_note, body.decode("utf-8"))
        with (
            patch.object(self.server.reviews, "_read_storage",
                         side_effect=ValueError(private_note)),
            self.assertLogs(prototype.LOGGER, level="ERROR") as captured,
        ):
            for response in (
                self.request(), self.post_note(note=private_note),
            ):
                self.assertEqual(response[0], 500)
                self.assertNotIn(private_note, response[2].decode("utf-8"))
        self.assertTrue(captured.output)
        self.assertNotIn(private_note, "\n".join(captured.output))
        self.assertFalse(self.review_file.exists())

    def test_concept_reviews_are_independent_and_preserve_history(
        self,
    ) -> None:
        """Round-trip each concept without changing any historical review."""
        historical_votes = {
            "concept-c": "reject", FIRST_ID: "keep",
            "study-00-retired": "reject", "study-101-retired": "keep",
            "concept-retired": "keep",
        }
        historical_notes = {
            concept_id: f"Historical note: {concept_id}\n  保留 🎉"
            for concept_id in historical_votes
        }
        votes = historical_votes.copy()
        notes = historical_notes.copy()
        expected = {"schemaVersion": 1, "votes": votes, "notes": notes}
        self.review_file.write_text(json.dumps(expected), encoding="utf-8")
        concept_ids = ("concept-a", "concept-b", "concept-e") + tuple(
            f"concept-c-{variant}" for variant in (
                "rail", "frame", "cap", "index", "footnote", "bracket",
                "telemetry", "stamp", "terminal", "fold",
            )
        ) + tuple(
            f"concept-d-{mode}" for mode in ("off", "signal", "scan", "quiet")
        )
        self.assertEqual(self.server.reviews.study_ids, {
            "concept-c", *concept_ids,
            *(f"study-{Path(name).stem}" for name in STUDY_FILES),
        })

        def assert_saved(
            response: tuple[int, dict[str, str], bytes],
        ) -> None:
            """Compare response, GET, disk, and a fresh store for every ID."""
            self.assertEqual(response[0], 200)
            self.assertEqual(json.loads(response[2]), expected)
            self.assertEqual(json.loads(self.request()[2]), expected)
            self.assertEqual(
                prototype.ReviewStore(self.review_file, self.studies).read(),
                expected,
            )
            self.assertEqual(
                json.loads(self.review_file.read_text(encoding="utf-8")),
                expected,
            )

        for concept_id in concept_ids:
            with self.subTest(concept_id=concept_id):
                for vote in ("keep", "reject"):
                    votes[concept_id] = vote
                    assert_saved(self.post(concept_id, vote))
                notes[concept_id] = f"Independent note: {concept_id}\n  筆記 🎉"
                assert_saved(self.post_note(concept_id, notes[concept_id]))
        for concept_id in concept_ids:
            with self.subTest(cleared_id=concept_id):
                votes.pop(concept_id)
                assert_saved(self.post(concept_id, None))
                notes.pop(concept_id)
                assert_saved(self.post_note(concept_id, ""))
        self.assertEqual(votes, historical_votes)
        self.assertEqual(notes, historical_notes)
        self.assertEqual(list(self.studies.glob("*.tmp")), [])

    def test_unknown_concepts_are_read_only_and_never_rewritten(self) -> None:
        """Preserve unlisted concept history and refuse writes and clears."""
        unknown_ids = (
            "concept-f", "concept-d", "concept-c-unknown", "concept-d-noise",
            "concept-a-rail", "concept-b-frame", "concept-c-rail-extra",
        )
        expected = {
            "schemaVersion": 1,
            "votes": {concept_id: "keep" for concept_id in unknown_ids},
            "notes": {
                concept_id: f"Retired note: {concept_id}"
                for concept_id in unknown_ids
            },
        }
        self.review_file.write_text(json.dumps(expected), encoding="utf-8")
        original = self.review_file.read_bytes()
        self.assertEqual(json.loads(self.request()[2]), expected)
        self.assertEqual(
            prototype.ReviewStore(self.review_file, self.studies).read(),
            expected,
        )
        self.assertEqual(self.review_file.read_bytes(), original)
        for concept_id in unknown_ids:
            with self.subTest(concept_id=concept_id):
                for vote in ("keep", "reject", None):
                    self.assertEqual(self.post(concept_id, vote)[0], 400)
                    with self.assertRaises(ValueError):
                        self.server.reviews.update(concept_id, vote)
                for note in ("New note", ""):
                    self.assertEqual(self.post_note(concept_id, note)[0], 400)
                    with self.assertRaises(ValueError):
                        self.server.reviews.update_note(concept_id, note)
                self.assertEqual(self.review_file.read_bytes(), original)

    def test_concept_c_vote_and_note_persistence(self) -> None:
        """Allow voting and notes for concept-c and preserve across reload."""
        status, _, body = self.post("concept-c", "keep")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["votes"]["concept-c"], "keep")

        status, _, body = self.post_note(
            "concept-c", "Concept C particle feedback",
        )
        self.assertEqual(status, 200)
        snapshot = json.loads(body)
        self.assertEqual(snapshot["votes"]["concept-c"], "keep")
        self.assertEqual(
            snapshot["notes"]["concept-c"], "Concept C particle feedback",
        )

        reloaded = prototype.ReviewStore(self.review_file, self.studies).read()
        self.assertEqual(reloaded["votes"]["concept-c"], "keep")
        self.assertEqual(
            reloaded["notes"]["concept-c"], "Concept C particle feedback",
        )


if __name__ == "__main__":
    unittest.main()
