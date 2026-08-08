import base64
import dataclasses
import hashlib
import http.client
import importlib.util
import ipaddress
import json
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest


MODULE_PATH = pathlib.Path(__file__).parents[2] / "ops" / "wwg-provisioner" / "provisioner.py"
SPEC = importlib.util.spec_from_file_location("wwg_provisioner", MODULE_PATH)
provisioner = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = provisioner
SPEC.loader.exec_module(provisioner)


def key(byte: int) -> str:
    return base64.b64encode(bytes([byte]) * 32).decode("ascii")


class FakeRunner:
    def __init__(self, server_key: str):
        self.server_key = server_key
        self.commands = []

    def __call__(self, command, **kwargs):
        self.commands.append((command, kwargs.get("input")))
        stdout = (self.server_key + "\n").encode() if command[-3:] == ["awg", "show", "awg0"] else b""
        if "public-key" in command:
            stdout = (self.server_key + "\n").encode()
        return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr=b"")


class ContainerConfigRunner(FakeRunner):
    def __init__(self, server_key: str, config: str):
        super().__init__(server_key)
        self.config = config

    def __call__(self, command, **kwargs):
        self.commands.append((command, kwargs.get("input")))
        if command[-2:] == ["cat", "/opt/amnezia/awg/awg0.conf"]:
            return subprocess.CompletedProcess(
                command, 0, stdout=self.config.encode("utf-8"), stderr=b"")
        if len(command) > 4 and command[4] == "sh":
            self.config = kwargs["input"].decode("utf-8")
            return subprocess.CompletedProcess(command, 0, stdout=b"", stderr=b"")
        stdout = (self.server_key + "\n").encode() if "public-key" in command else b""
        return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr=b"")


class ConcurrentHandshakeContext:
    def __init__(self):
        self.calls = 0
        self.first_started = threading.Event()
        self.release_first = threading.Event()
        self.lock = threading.Lock()

    def wrap_socket(self, request, server_side=True):
        del server_side
        with self.lock:
            self.calls += 1
            call = self.calls
        if call == 1:
            self.first_started.set()
            self.release_first.wait(timeout=2)
            raise OSError("simulated stalled TLS client")
        return request


class ProvisioningStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config_path = pathlib.Path(self.temp.name) / "awg0.conf"
        self.original_config = (
            "[Interface]\nPrivateKey = hidden\n\n"
            f"[Peer]\nPublicKey = {key(2)}\nPresharedKey = {key(12)}\nAllowedIPs = 10.9.1.2/32\n\n"
            f"[Peer]\nPublicKey = {key(3)}\nPresharedKey = {key(13)}\nAllowedIPs = 10.9.1.3/32\n"
        )
        self.config_path.write_text(self.original_config, encoding="utf-8")
        self.server_key = key(1)
        self.profile = provisioner.Profile(
            token_sha256=hashlib.sha256(b"a" * 48).hexdigest(),
            container="test-awg",
            interface="awg0",
            awg_binary="awg",
            config_path=self.config_path,
            subnet=ipaddress.ip_network("10.9.1.0/24"),
            server_public_key=self.server_key,
            max_peers=8,
        )
        self.runner = FakeRunner(self.server_key)
        self.store = provisioner.ProvisioningStore(
            self.profile, b"rollback-secret" * 4, runner=self.runner, rollback_ttl=60)

    def test_creates_unique_peer_idempotently_and_rolls_it_back(self):
        public_key = key(4)
        psk = key(14)
        created = self.store.create_peer(public_key, psk, "request-entry-0001")
        self.assertTrue(created.created)
        self.assertEqual(created.client_ip, "10.9.1.4/32")
        text = self.config_path.read_text(encoding="utf-8")
        self.assertIn(provisioner.PROVISIONED_MARKER, text)
        self.assertIn(public_key, text)
        self.assertIn("AllowedIPs = 10.9.1.4/32", text)

        duplicate = self.store.create_peer(public_key, psk, "request-entry-0001")
        self.assertFalse(duplicate.created)
        self.assertEqual(duplicate.client_ip, created.client_ip)
        self.assertEqual(self.config_path.read_text(encoding="utf-8").count(public_key), 1)

        self.store.rollback_peer(public_key, duplicate.rollback_token)
        text = self.config_path.read_text(encoding="utf-8")
        self.assertNotIn(public_key, text)
        self.assertIn(key(2), text)
        self.assertIn(key(3), text)
        self.assertEqual(text, self.original_config)
        self.assertTrue(any(command[-2:] == [public_key, "remove"] for command, _ in self.runner.commands))

    def test_rejects_collision_with_existing_or_different_psk(self):
        with self.assertRaises(provisioner.Conflict):
            self.store.create_peer(key(2), key(12), "request-entry-0002")

        created = self.store.create_peer(key(5), key(15), "request-entry-0003")
        self.assertTrue(created.created)
        with self.assertRaises(provisioner.Conflict):
            self.store.create_peer(key(5), key(16), "request-entry-0003")

    def test_rollback_keeps_marker_for_following_provisioned_peer(self):
        first = self.store.create_peer(key(20), key(30), "request-entry-0020")
        second = self.store.create_peer(key(21), key(31), "request-entry-0021")
        self.store.rollback_peer(key(20), first.rollback_token)

        text = self.config_path.read_text(encoding="utf-8")
        peer = next(item for item in provisioner.parse_peer_blocks(text) if item.public_key == key(21))
        self.assertTrue(peer.provisioned)
        self.store.rollback_peer(key(21), second.rollback_token)
        self.assertEqual(self.config_path.read_text(encoding="utf-8"), self.original_config)

    def test_rejects_expired_or_cross_peer_rollback(self):
        created = self.store.create_peer(key(6), key(16), "request-entry-0004")
        with self.assertRaises(provisioner.Unauthorized):
            self.store.rollback_peer(key(7), created.rollback_token)

        expired_store = provisioner.ProvisioningStore(
            self.profile, b"rollback-secret" * 4, runner=self.runner, rollback_ttl=-1)
        expired = expired_store.create_peer(key(8), key(18), "request-entry-0005")
        time.sleep(0.01)
        with self.assertRaises(provisioner.Unauthorized):
            expired_store.rollback_peer(key(8), expired.rollback_token)

    def test_enforces_peer_limit(self):
        limited = dataclasses.replace(self.profile, max_peers=2)
        store = provisioner.ProvisioningStore(limited, b"rollback-secret" * 4, runner=self.runner)
        with self.assertRaises(provisioner.PoolExhausted):
            store.create_peer(key(9), key(19), "request-entry-0006")

    def test_allocates_from_large_pool_with_2048_peer_limit(self):
        large_profile = dataclasses.replace(
            self.profile,
            subnet=ipaddress.ip_network("10.90.0.0/20"),
            max_peers=2048,
        )
        store = provisioner.ProvisioningStore(
            large_profile, b"rollback-secret" * 4, runner=self.runner)
        created = store.create_peer(key(40), key(41), "request-large-pool-0040")
        self.assertEqual(created.client_ip, "10.90.0.2/32")

    def test_large_pool_defaults_to_2048_peer_limit(self):
        profile = provisioner.Profile.from_json({
            "token_sha256": hashlib.sha256(b"default-large-pool").hexdigest(),
            "container": "test-awg",
            "config_path": str(self.config_path),
            "subnet": "10.90.0.0/20",
            "server_public_key": self.server_key,
        })
        self.assertEqual(profile.max_peers, 2048)

    def test_container_owned_config_is_updated_without_secrets_in_argv(self):
        container_profile = dataclasses.replace(
            self.profile,
            config_path=None,
            container_config_path="/opt/amnezia/awg/awg0.conf",
        )
        runner = ContainerConfigRunner(
            self.server_key, self.config_path.read_text(encoding="utf-8"))
        store = provisioner.ProvisioningStore(
            container_profile,
            b"rollback-secret" * 4,
            runner=runner,
            rollback_ttl=60,
            lock_directory=pathlib.Path(self.temp.name) / "locks",
        )

        store.verify_config_exists()
        psk = key(25)
        created = store.create_peer(key(15), psk, "request-container-0015")
        self.assertEqual(created.client_ip, "10.9.1.4/32")
        self.assertIn(key(15), runner.config)
        self.assertFalse(any(psk in " ".join(command) for command, _ in runner.commands))

        store.rollback_peer(key(15), created.rollback_token)
        self.assertNotIn(key(15), runner.config)
        self.assertEqual(runner.config, self.original_config)


class ProvisioningServerTest(unittest.TestCase):
    def test_stalled_handshake_does_not_block_other_clients(self):
        context = ConcurrentHandshakeContext()
        server = provisioner.ProvisioningServer(
            ("127.0.0.1", 0), provisioner.ProvisioningHandler, object(), context)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        first = socket.create_connection(server.server_address, timeout=2)
        self.assertTrue(context.first_started.wait(timeout=1))

        connection = http.client.HTTPConnection(*server.server_address, timeout=1)
        connection.request("GET", "/healthz")
        response = connection.getresponse()
        self.assertEqual(response.status, 200)
        self.assertEqual(json.loads(response.read()), {"status": "ok"})
        connection.close()

        context.release_first.set()
        first.close()
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
