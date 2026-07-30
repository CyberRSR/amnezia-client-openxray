"""Redacted AWG3 peer monitor for the WWG v0.7 validation window.

Credentials are read only from the process environment and are never written
to the output. The remote command itself emits only allowed IPs, handshake
ages, and transfer counters.
"""

from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone

import paramiko


HOSTS = (
    (
        "first",
        os.environ.get("WWG_FIRST_HOST", ""),
        "WWG_FIRST_PASS",
        os.environ.get("WWG_FIRST_CONTAINER", "amnezia-wwg-entry-v3"),
        os.environ.get("WWG_FIRST_PEER_PREFIX", "10.9.1."),
    ),
    (
        "second",
        os.environ.get("WWG_SECOND_HOST", ""),
        "WWG_SECOND_PASS",
        os.environ.get("WWG_SECOND_CONTAINER", "amnezia-awg3"),
        os.environ.get("WWG_SECOND_PEER_PREFIX", "10.8.2."),
    ),
)
REMOTE = r"""set -eu
container="$1"
prefix="$2"
if [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || true)" != "true" ]; then
  echo "container_state=not_running"
  exit 0
fi
docker exec "$container" awg show awg0 | awk -v p="$prefix" '
  /allowed ips:/ { ip=$3; if (index(ip,p)==1) keep=1; else keep=0 }
  keep && /latest handshake:/ { handshake=$0 }
  keep && /transfer:/ { print ip "|" handshake "|" $0; keep=0 }
'"""


def sample(label: str, host: str, env_name: str, container: str, prefix: str) -> dict:
    if not host:
        return {"server": label, "error": "missing host environment"}
    password = os.environ.get(env_name)
    if password is None:
        return {"server": label, "error": "missing credential environment"}
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(
            host,
            username="root",
            password=password,
            timeout=10,
            look_for_keys=False,
            allow_agent=False,
        )
        command = f"sh -s -- {container} {prefix} <<'WWG_MONITOR_EOF'\n{REMOTE}\nWWG_MONITOR_EOF"
        _, stdout, stderr = client.exec_command(command)
        output = stdout.read().decode("utf-8", "replace").strip()
        error = stderr.read().decode("utf-8", "replace").strip()
        peers = []
        for line in output.splitlines():
            if "|" not in line:
                continue
            ip, handshake, transfer = line.split("|", 2)
            peers.append({"allowed_ip": ip, "latest_handshake": handshake, "transfer": transfer})
        result = {"server": label, "container": container, "peers": peers}
        if error:
            result["remote_error"] = error[:240]
        return result
    except Exception as exc:  # noqa: BLE001 - diagnostic process must continue
        return {"server": label, "error": type(exc).__name__}
    finally:
        client.close()


def main() -> None:
    duration = int(os.environ.get("WWG_MONITOR_MINUTES", "35"))
    interval = max(10, min(60, int(os.environ.get("WWG_MONITOR_INTERVAL", "30"))))
    output_path = os.environ.get(
        "WWG_MONITOR_OUTPUT",
        os.path.abspath("server-monitor.jsonl"),
    )
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    deadline = time.monotonic() + duration * 60
    with open(output_path, "a", encoding="utf-8") as output:
        while time.monotonic() < deadline:
            record = {
                "timestamp_utc": datetime.now(timezone.utc).isoformat(),
                "servers": [sample(*host) for host in HOSTS],
            }
            output.write(json.dumps(record, separators=(",", ":")) + "\n")
            output.flush()
            time.sleep(interval)


if __name__ == "__main__":
    main()
