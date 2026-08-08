#!/usr/bin/env python3
"""Capability-scoped WWG peer provisioner.

The service intentionally has no generic command endpoint and never receives
client private keys. A bearer capability selects one preconfigured container;
the caller supplies a freshly generated X25519 public key and preshared key.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import contextlib
import dataclasses
import datetime as dt
import hashlib
import hmac
import http.server
import ipaddress
import json
import logging
import os
import pathlib
import re
import secrets
import ssl
import subprocess
import tempfile
import threading
import time
from collections import defaultdict, deque
from typing import Callable, Iterator

try:
    import fcntl  # type: ignore
except ImportError:  # pragma: no cover - Windows unit tests use the thread lock.
    fcntl = None


LOG = logging.getLogger("wwg-peer-provisioner")
KEY_RE = re.compile(r"^[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=$")
HEX_256_RE = re.compile(r"^[0-9a-f]{64}$")
PROVISIONED_MARKER = "# WWG-PROVISIONED "


class ProvisioningError(Exception):
    status = 500
    code = "internal_error"


class BadRequest(ProvisioningError):
    status = 400
    code = "bad_request"


class Unauthorized(ProvisioningError):
    status = 401
    code = "unauthorized"


class Conflict(ProvisioningError):
    status = 409
    code = "peer_conflict"


class RateLimited(ProvisioningError):
    status = 429
    code = "rate_limited"


class PoolExhausted(ProvisioningError):
    status = 507
    code = "address_pool_exhausted"


def _strict_b64_key(value: str) -> str:
    value = value.strip()
    if not KEY_RE.fullmatch(value):
        raise BadRequest("invalid WireGuard key encoding")
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise BadRequest("invalid WireGuard key encoding") from exc
    if len(decoded) != 32:
        raise BadRequest("WireGuard keys must contain 32 bytes")
    return value


def _atomic_rewrite_in_place(path: pathlib.Path, content: str) -> None:
    """Rewrite the existing inode so a running read-only bind mount sees it."""
    encoded = content.encode("utf-8")
    directory = path.parent
    with tempfile.NamedTemporaryFile(dir=directory, prefix=path.name + ".", delete=False) as tmp:
        tmp.write(encoded)
        tmp.flush()
        os.fsync(tmp.fileno())
        temp_name = tmp.name
    try:
        with path.open("r+b") as destination, open(temp_name, "rb") as source:
            destination.seek(0)
            destination.write(source.read())
            destination.truncate()
            destination.flush()
            os.fsync(destination.fileno())
    finally:
        pathlib.Path(temp_name).unlink(missing_ok=True)


@dataclasses.dataclass(frozen=True)
class PeerBlock:
    start: int
    end: int
    public_key: str
    preshared_key: str
    client_ip: str
    provisioned: bool


def parse_peer_blocks(config: str) -> list[PeerBlock]:
    matches = list(re.finditer(r"(?m)^\[Peer\]\s*$", config))
    starts: list[tuple[int, bool]] = []
    for match in matches:
        block_start = match.start()
        marker_start = config.rfind("\n", 0, block_start - 1) + 1
        previous_line = config[marker_start:block_start].strip()
        provisioned = previous_line.startswith(PROVISIONED_MARKER)
        if provisioned:
            prefix_match = re.search(r"\bprefix_len=(\d+)\b", previous_line)
            if prefix_match:
                prefix_len = int(prefix_match.group(1))
                prefix_start = marker_start - prefix_len
                if (0 <= prefix_start <= marker_start
                        and not config[prefix_start:marker_start].strip()):
                    marker_start = prefix_start
        starts.append((marker_start if provisioned else block_start, provisioned))

    blocks: list[PeerBlock] = []
    for index, match in enumerate(matches):
        start, provisioned = starts[index]
        end = starts[index + 1][0] if index + 1 < len(matches) else len(config)
        body = config[match.start():end]

        def value(name: str) -> str:
            found = re.search(rf"(?mi)^\s*{re.escape(name)}\s*=\s*([^#\r\n]+)", body)
            return found.group(1).strip() if found else ""

        allowed = value("AllowedIPs").split(",", 1)[0].strip()
        client_ip = allowed.split("/", 1)[0]
        blocks.append(PeerBlock(
            start=start,
            end=end,
            public_key=value("PublicKey"),
            preshared_key=value("PresharedKey"),
            client_ip=client_ip,
            provisioned=provisioned,
        ))
    return blocks


@dataclasses.dataclass(frozen=True)
class Profile:
    token_sha256: str
    container: str
    interface: str
    awg_binary: str
    config_path: pathlib.Path | None
    subnet: ipaddress.IPv4Network
    server_public_key: str
    max_peers: int
    container_config_path: str | None = None

    @staticmethod
    def from_json(value: dict) -> "Profile":
        token_sha256 = str(value.get("token_sha256", "")).strip().lower()
        if not HEX_256_RE.fullmatch(token_sha256):
            raise ValueError("profile token_sha256 must be 64 lowercase hex characters")
        subnet = ipaddress.ip_network(str(value["subnet"]), strict=True)
        if not isinstance(subnet, ipaddress.IPv4Network) or subnet.prefixlen > 30:
            raise ValueError("profile subnet must be an IPv4 network with usable hosts")
        server_public_key = _strict_b64_key(str(value["server_public_key"]))
        max_peers = int(value.get("max_peers", min(2048, subnet.num_addresses - 2)))
        if max_peers < 1 or max_peers > subnet.num_addresses - 2:
            raise ValueError("profile max_peers is outside the subnet capacity")
        container = str(value["container"]).strip()
        interface = str(value.get("interface", "awg0")).strip()
        awg_binary = str(value.get("awg_binary", "awg")).strip()
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", container):
            raise ValueError("invalid container name")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", interface):
            raise ValueError("invalid interface name")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", awg_binary):
            raise ValueError("invalid AWG binary name")
        host_config_value = str(value.get("config_path", "")).strip()
        container_config_path = str(value.get("container_config_path", "")).strip()
        if bool(host_config_value) == bool(container_config_path):
            raise ValueError("profile requires exactly one config_path or container_config_path")
        if container_config_path:
            parsed_container_path = pathlib.PurePosixPath(container_config_path)
            if (not parsed_container_path.is_absolute()
                    or ".." in parsed_container_path.parts
                    or not re.fullmatch(r"/[A-Za-z0-9_./-]+", container_config_path)):
                raise ValueError("invalid container_config_path")
        return Profile(
            token_sha256=token_sha256,
            container=container,
            interface=interface,
            awg_binary=awg_binary,
            config_path=pathlib.Path(host_config_value) if host_config_value else None,
            subnet=subnet,
            server_public_key=server_public_key,
            max_peers=max_peers,
            container_config_path=container_config_path or None,
        )


@dataclasses.dataclass(frozen=True)
class CreatedPeer:
    public_key: str
    client_ip: str
    server_public_key: str
    rollback_token: str
    created: bool


Runner = Callable[..., subprocess.CompletedProcess]


class ProvisioningStore:
    def __init__(self, profile: Profile, rollback_secret: bytes,
                 runner: Runner = subprocess.run, rollback_ttl: int = 180,
                 lock_directory: pathlib.Path = pathlib.Path("/run/wwg-peer-provisioner")):
        self.profile = profile
        self.rollback_secret = rollback_secret
        self.runner = runner
        self.rollback_ttl = rollback_ttl
        self.lock_directory = lock_directory
        self._lock = threading.Lock()

    @contextlib.contextmanager
    def _locked(self) -> Iterator[None]:
        if self.profile.config_path is not None:
            lock_path = self.profile.config_path.with_suffix(
                self.profile.config_path.suffix + ".provision.lock")
        else:
            lock_path = self.lock_directory / (
                self.profile.token_sha256[:24] + ".lock")
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        with self._lock, lock_path.open("a+b") as lock_file:
            if fcntl is not None:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                if fcntl is not None:
                    fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)

    def _run_awg(self, *args: str, stdin: str | None = None) -> subprocess.CompletedProcess:
        command = [
            "docker", "exec", "-i", self.profile.container,
            self.profile.awg_binary, *args,
        ]
        return self.runner(
            command,
            input=None if stdin is None else stdin.encode("ascii"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=15,
            check=True,
        )

    def _read_config(self) -> str:
        if self.profile.config_path is not None:
            return self.profile.config_path.read_text(encoding="utf-8")
        if self.profile.container_config_path is None:  # Defensive; Profile validates this.
            raise RuntimeError("profile has no configuration backend")
        result = self.runner(
            ["docker", "exec", "-i", self.profile.container,
             "cat", self.profile.container_config_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=15,
            check=True,
        )
        if len(result.stdout) > 1024 * 1024:
            raise RuntimeError("container configuration is unexpectedly large")
        return result.stdout.decode("utf-8", errors="strict")

    def _write_config(self, content: str) -> None:
        if len(content.encode("utf-8")) > 1024 * 1024:
            raise RuntimeError("refusing to write an unexpectedly large configuration")
        if self.profile.config_path is not None:
            _atomic_rewrite_in_place(self.profile.config_path, content)
            return
        if self.profile.container_config_path is None:  # Defensive; Profile validates this.
            raise RuntimeError("profile has no configuration backend")

        # A container-owned configuration has no host bind inode to preserve.
        # Prepare it beside the target, copy numeric ownership/mode, then use an
        # atomic rename. The configuration body is sent on stdin and never put
        # in argv, process listings or logs.
        script = (
            "set -eu\n"
            "path=$1\n"
            "dir=${path%/*}\n"
            "tmp=$(mktemp \"$dir/.wwg-peer.XXXXXX\")\n"
            "trap 'rm -f \"$tmp\"' EXIT\n"
            "cat >\"$tmp\"\n"
            "chmod \"$(stat -c %a \"$path\")\" \"$tmp\"\n"
            "chown \"$(stat -c %u \"$path\"):$(stat -c %g \"$path\")\" \"$tmp\"\n"
            "mv -f \"$tmp\" \"$path\"\n"
            "trap - EXIT\n"
        )
        self.runner(
            ["docker", "exec", "-i", self.profile.container,
             "sh", "-c", script, "--", self.profile.container_config_path],
            input=content.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=15,
            check=True,
        )

    def verify_config_exists(self) -> None:
        if self.profile.config_path is not None:
            if not self.profile.config_path.is_file():
                raise ValueError(f"profile config does not exist: {self.profile.config_path}")
            return
        self._read_config()

    def verify_live_server(self) -> None:
        result = self._run_awg("show", self.profile.interface, "public-key")
        live_key = result.stdout.decode("ascii", errors="strict").strip()
        if not hmac.compare_digest(live_key, self.profile.server_public_key):
            raise RuntimeError("configured server public key does not match the live interface")

    def _next_ip(self, peers: list[PeerBlock]) -> ipaddress.IPv4Address:
        used = {ipaddress.ip_address(peer.client_ip) for peer in peers if peer.client_ip}
        # The first usable address belongs to the server interface.
        for candidate in list(self.profile.subnet.hosts())[1:]:
            if candidate not in used:
                return candidate
        raise PoolExhausted("no free peer address")

    def _rollback_token(self, public_key: str, client_ip: str, deadline: int) -> str:
        payload = json.dumps({
            "public_key": public_key,
            "client_ip": client_ip,
            "deadline": deadline,
            "profile": self.profile.token_sha256[:16],
        }, separators=(",", ":"), sort_keys=True).encode("utf-8")
        signature = hmac.new(self.rollback_secret, payload, hashlib.sha256).digest()
        return (base64.urlsafe_b64encode(payload).rstrip(b"=") + b"." +
                base64.urlsafe_b64encode(signature).rstrip(b"=")).decode("ascii")

    def _verify_rollback_token(self, token: str, public_key: str) -> dict:
        try:
            payload_part, signature_part = token.encode("ascii").split(b".", 1)
            payload = base64.urlsafe_b64decode(payload_part + b"=" * (-len(payload_part) % 4))
            signature = base64.urlsafe_b64decode(signature_part + b"=" * (-len(signature_part) % 4))
            expected = hmac.new(self.rollback_secret, payload, hashlib.sha256).digest()
            value = json.loads(payload)
        except (ValueError, UnicodeError, json.JSONDecodeError, binascii.Error) as exc:
            raise Unauthorized("invalid rollback token") from exc
        if not hmac.compare_digest(signature, expected):
            raise Unauthorized("invalid rollback token")
        if value.get("public_key") != public_key:
            raise Unauthorized("rollback token does not match the peer")
        if value.get("profile") != self.profile.token_sha256[:16]:
            raise Unauthorized("rollback token does not match the profile")
        if int(value.get("deadline", 0)) < int(time.time()):
            raise Unauthorized("rollback token expired")
        return value

    def create_peer(self, public_key: str, preshared_key: str, request_id: str) -> CreatedPeer:
        public_key = _strict_b64_key(public_key)
        preshared_key = _strict_b64_key(preshared_key)
        request_hash = hashlib.sha256(request_id.encode("utf-8")).hexdigest()[:16]

        with self._locked():
            original = self._read_config()
            peers = parse_peer_blocks(original)
            duplicate = next((peer for peer in peers if peer.public_key == public_key), None)
            if duplicate:
                if not duplicate.provisioned or not hmac.compare_digest(duplicate.preshared_key, preshared_key):
                    raise Conflict("public key already exists with different peer data")
                deadline = int(time.time()) + self.rollback_ttl
                return CreatedPeer(
                    public_key=public_key,
                    client_ip=f"{duplicate.client_ip}/32",
                    server_public_key=self.profile.server_public_key,
                    rollback_token=self._rollback_token(public_key, duplicate.client_ip, deadline),
                    created=False,
                )
            if len(peers) >= self.profile.max_peers:
                raise PoolExhausted("peer limit reached")

            client_ip = str(self._next_ip(peers))
            timestamp = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
            separator = "" if not original or original.endswith("\n") else "\n"
            updated = original + separator + (
                f"\n{PROVISIONED_MARKER}{timestamp} request={request_hash} "
                f"prefix_len={len(separator) + 1}\n"
                "[Peer]\n"
                f"PublicKey = {public_key}\n"
                f"PresharedKey = {preshared_key}\n"
                f"AllowedIPs = {client_ip}/32\n"
            )
            self._write_config(updated)
            try:
                self._run_awg(
                    "set", self.profile.interface,
                    "peer", public_key,
                    "preshared-key", "/dev/stdin",
                    "allowed-ips", f"{client_ip}/32",
                    stdin=preshared_key + "\n",
                )
            except Exception:
                self._write_config(original)
                raise

            deadline = int(time.time()) + self.rollback_ttl
            return CreatedPeer(
                public_key=public_key,
                client_ip=f"{client_ip}/32",
                server_public_key=self.profile.server_public_key,
                rollback_token=self._rollback_token(public_key, client_ip, deadline),
                created=True,
            )

    def rollback_peer(self, public_key: str, rollback_token: str) -> None:
        public_key = _strict_b64_key(public_key)
        token_data = self._verify_rollback_token(rollback_token, public_key)
        expected_ip = str(token_data["client_ip"])

        with self._locked():
            original = self._read_config()
            peers = parse_peer_blocks(original)
            peer = next((item for item in peers if item.public_key == public_key), None)
            if peer is None:
                return
            if not peer.provisioned or peer.client_ip != expected_ip:
                raise Unauthorized("only the newly provisioned peer can be rolled back")

            updated = original[:peer.start] + original[peer.end:]
            self._write_config(updated)
            try:
                self._run_awg("set", self.profile.interface, "peer", public_key, "remove")
            except Exception:
                self._write_config(original)
                raise


class RateLimiter:
    def __init__(self, requests: int, window_seconds: int):
        self.requests = requests
        self.window_seconds = window_seconds
        self._events: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            events = self._events[key]
            while events and events[0] <= now - self.window_seconds:
                events.popleft()
            if len(events) >= self.requests:
                raise RateLimited("too many provisioning requests")
            events.append(now)


class ProvisioningApplication:
    def __init__(self, stores: dict[str, ProvisioningStore], rate_limiter: RateLimiter):
        self.stores = stores
        self.rate_limiter = rate_limiter

    def authenticate(self, authorization: str, remote_address: str) -> ProvisioningStore:
        if not authorization.startswith("Bearer "):
            raise Unauthorized("missing bearer capability")
        token = authorization[7:].strip()
        if len(token) < 32 or len(token) > 256:
            raise Unauthorized("invalid bearer capability")
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        store = None
        for expected, candidate in self.stores.items():
            if hmac.compare_digest(token_hash, expected):
                store = candidate
        if store is None:
            raise Unauthorized("invalid bearer capability")
        self.rate_limiter.check(token_hash[:16] + ":" + remote_address)
        return store


class ProvisioningHandler(http.server.BaseHTTPRequestHandler):
    server_version = "WWGPeerProvisioner/1"
    protocol_version = "HTTP/1.1"

    @property
    def application(self) -> ProvisioningApplication:
        return self.server.application  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args) -> None:
        LOG.info("client=%s %s", self.client_address[0], fmt % args)

    def _json(self, status: int, value: dict) -> None:
        body = json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _payload(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise BadRequest("invalid content length") from exc
        if length < 2 or length > 4096:
            raise BadRequest("invalid request size")
        try:
            value = json.loads(self.rfile.read(length))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise BadRequest("invalid JSON") from exc
        if not isinstance(value, dict):
            raise BadRequest("JSON body must be an object")
        return value

    def do_GET(self) -> None:  # noqa: N802
        if self.path != "/healthz":
            self._json(404, {"error": "not_found"})
            return
        self._json(200, {"status": "ok"})

    def do_POST(self) -> None:  # noqa: N802
        try:
            store = self.application.authenticate(
                self.headers.get("Authorization", ""), self.client_address[0])
            payload = self._payload()
            if self.path == "/v1/peers":
                request_id = str(payload.get("request_id", ""))
                if not re.fullmatch(r"[A-Za-z0-9_.-]{8,128}", request_id):
                    raise BadRequest("invalid request_id")
                peer = store.create_peer(
                    str(payload.get("public_key", "")),
                    str(payload.get("preshared_key", "")),
                    request_id,
                )
                self._json(201 if peer.created else 200, dataclasses.asdict(peer))
                return
            if self.path == "/v1/peers/rollback":
                store.rollback_peer(
                    str(payload.get("public_key", "")),
                    str(payload.get("rollback_token", "")),
                )
                self._json(200, {"rolled_back": True})
                return
            self._json(404, {"error": "not_found"})
        except ProvisioningError as exc:
            self._json(exc.status, {"error": exc.code})
        except (OSError, subprocess.SubprocessError):
            LOG.exception("provisioning backend failure")
            self._json(500, {"error": "backend_failure"})
        except Exception:
            LOG.exception("unexpected provisioning failure")
            self._json(500, {"error": "internal_error"})


class ProvisioningServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, application: ProvisioningApplication,
                 tls_context: ssl.SSLContext):
        super().__init__(address, handler)
        self.application = application
        self.tls_context = tls_context

    def process_request_thread(self, request, client_address) -> None:
        # Wrap each accepted socket in its worker thread. Wrapping the listening
        # socket would perform the TLS handshake in the single accept loop, so
        # one client that connects without sending a ClientHello could block all
        # exports indefinitely.
        request.settimeout(10)
        try:
            tls_request = self.tls_context.wrap_socket(request, server_side=True)
            tls_request.settimeout(15)
        except (OSError, ssl.SSLError, TimeoutError):
            LOG.warning("TLS handshake rejected client=%s", client_address[0])
            self.shutdown_request(request)
            return
        super().process_request_thread(tls_request, client_address)


def load_application(config_path: pathlib.Path) -> tuple[dict, ProvisioningApplication]:
    raw = json.loads(config_path.read_text(encoding="utf-8"))
    secret_path = pathlib.Path(raw["rollback_secret_file"])
    rollback_secret = secret_path.read_bytes().strip()
    if len(rollback_secret) < 32:
        raise ValueError("rollback secret must contain at least 32 bytes")

    stores: dict[str, ProvisioningStore] = {}
    for value in raw["profiles"]:
        profile = Profile.from_json(value)
        if profile.token_sha256 in stores:
            raise ValueError("duplicate profile token hash")
        store = ProvisioningStore(
            profile,
            rollback_secret,
            lock_directory=pathlib.Path(raw.get(
                "lock_directory", "/run/wwg-peer-provisioner")),
        )
        store.verify_config_exists()
        stores[profile.token_sha256] = store
    if not stores:
        raise ValueError("at least one provisioning profile is required")
    limiter = RateLimiter(int(raw.get("rate_limit_requests", 20)),
                          int(raw.get("rate_limit_window_seconds", 60)))
    return raw, ProvisioningApplication(stores, limiter)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=pathlib.Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    raw, application = load_application(args.config)
    for store in application.stores.values():
        store.verify_live_server()
    if args.check:
        LOG.info("configuration and live interfaces verified")
        return 0

    address = (str(raw.get("listen_host", "0.0.0.0")), int(raw["listen_port"]))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(raw["tls_certificate"], raw["tls_private_key"])
    server = ProvisioningServer(address, ProvisioningHandler, application, context)
    LOG.info("WWG peer provisioner listening on %s:%d with %d profile(s)",
             address[0], address[1], len(application.stores))
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
