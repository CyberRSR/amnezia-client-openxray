# WWG AmneziaWG v3 server image

This directory contains the reproducible, secret-free server image used for
WWG v0.7. It is based on the official AmneziaWG Go Dockerfile, while pinning:

- `amneziawg-go` v3.0.1 commit
  `9f5d948bc72cc554791cfe0fb91527e4acfb6b79`;
- `amneziawg-tools` v1.0.20250901 commit
  `5e882890fbca2316f8ca40e992789d24f67f0118`;
- the multi-platform manifests for `golang:1.25.0` and `alpine:3.19`;
- SHA-256 for both downloaded archives.

The tools release does not yet parse AWG3-only configuration keys. The
`awg-uapi-config` helper applies `HeaderProtectionKey` and optional AWG3 range
fields directly through the official userspace UAPI after `awg-quick` creates
the interface, then reads the UAPI state back and verifies the HPK.

Server keys, peer keys, preshared keys, HPKs, client profiles and passwords
must stay outside the repository. Mount the accepted `awg-quick` configuration
read-only at `/etc/amneziawg/awg0.conf` and the hexadecimal HPK read-only at
`/run/secrets/awg3_hpk.hex`.

Example runtime shape (values are intentionally placeholders):

```sh
docker run -d \
  --name amnezia-awg3 \
  --restart unless-stopped \
  --cap-add NET_ADMIN \
  --device /dev/net/tun:/dev/net/tun \
  --sysctl net.ipv4.conf.all.src_valid_mark=1 \
  --sysctl net.ipv4.ip_forward=1 \
  -p 35162:35162/udp \
  -e AWG_VPN_SUBNET=10.8.2.0/24 \
  -v /opt/amnezia-awg3/awg0.conf:/etc/amneziawg/awg0.conf:ro \
  -v /opt/amnezia-awg3/hpk.hex:/run/secrets/awg3_hpk.hex:ro \
  amnezia-local/amneziawg-go:3.0.1
```

Use a separate key, peer and tunnel address for every device. Do not replace
or remove legacy v2 containers during migration.

The optional validation monitors are parameterized and do not contain
credentials or deployment host addresses. Example:

```powershell
.\simultaneous-monitor.ps1 `
  -PhoneSerial <phone-adb-serial> `
  -PhoneName phone `
  -EmulatorSerial <emulator-adb-serial> `
  -EmulatorName emulator `
  -OutputPath .\simultaneous-monitor.jsonl
```

For a repeatable data-plane load test without depending on the Speedtest UI,
run fixed-size HTTPS transfers on each connected device. The response body is
discarded on the device and each round is recorded as JSONL:

```powershell
.\device-load-test.ps1 `
  -Serial <adb-serial> `
  -Rounds 6 `
  -BytesPerRound 50000000 `
  -OutputPath .\device-load-test.jsonl
```

`server-monitor.py` reads the two hosts from `WWG_FIRST_HOST` and
`WWG_SECOND_HOST`, and their transient SSH passwords from `WWG_FIRST_PASS` and
`WWG_SECOND_PASS`. Its JSONL output is redacted to peer tunnel addresses,
handshake ages, transfer counters and container state.
