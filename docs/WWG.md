# WWG on Android

WWG is an Android-only chain of two AmneziaWG tunnels. Both layers must use the same generation: v2/v2 or v3/v3.

```text
Android app -> entry AWG netstack -> local UDP relay -> exit AWG -> internet
```

The entry tunnel runs in the official AmneziaWG Go netstack and exposes a loopback UDP relay. The exit tunnel is the only layer that creates the Android `VpnService` interface. Entry sockets are protected with `VpnService.protect`, so they cannot loop back through the exit VPN.

DNS follows the same chain as payload traffic: the query enters the exit tunnel and is then carried through the entry tunnel. IPv4-only profiles do not advertise an IPv6 default route, which prevents IPv6 bypass.

## v2 and v3 profiles

Import the entry AmneziaWG `.conf` first and the exit `.conf` second. Legacy v2/v2 profiles remain supported without conversion. A v3/v3 profile is identified by its AWG3 fields, including `HeaderProtectionKey`; the underlying `protocol_version` remains `2`, as required by AmneziaWG.

WWG rejects mixed v2/v3 chains, incomplete AWG settings, malformed AWG3 ranges, `S1..S4` values below 8 in v3, and a client/server `HeaderProtectionKey` mismatch. Empty `I1..I5` fields are treated as absent.

The persisted container schema is:

```text
containers[].container = "amnezia-wwg"
containers[].wwg.underlay_awg = AwgProtocolConfig
containers[].wwg.overlay_awg  = AwgProtocolConfig
containers[].wwg.provisioning = optional peer-provisioning capability
```

The Android runtime receives:

```text
runtime.protocol = "WWG"
runtime.awg_underlay_config_data = <entry client config>
runtime.awg_overlay_config_data  = <exit client config>
```

## Safe file and QR export

One WireGuard key/address pair must never be active on two devices. For a managed WWG profile, **Create a separate WWG profile** contacts the entry and exit provisioners over certificate-pinned HTTPS and transactionally creates:

- a new X25519 private/public key pair for each layer;
- a new pre-shared key for each layer;
- a new unique `/32` tunnel address and server peer for each layer.

The donor profile is not modified. The file and QR displayed by the same share operation represent the same newly created recipient profile; every later export operation creates another profile. AWG server-wide obfuscation values, including HPK/J/S/H settings, are intentionally preserved and are not device identities.

An imported legacy profile that has no scoped provisioning capability can still connect, but the client refuses to clone it. It must be reissued once as a managed profile; otherwise an export would duplicate its peer and reproduce the multi-device collision.

If entry provisioning succeeds but exit provisioning fails, the newly created entry peer is rolled back. The server API is capability-scoped, rate-limited, uses short-lived rollback receipts, and cannot execute arbitrary commands.

## Lifecycle and recovery

A mutex and generation counter serialize the entire chain. A restart closes `overlay -> relay -> underlay`, then starts `underlay -> relay -> overlay`; a second worker or relay cannot start in parallel.

Health checks run every five seconds. A partial failure of one endpoint is logged but does not tear down a usable VPN. Two complete failed VPN-bound probe rounds trigger one restart for the current generation. Retry delays use bounded `5/15/30/60/120` second backoff with jitter and reset after two stable minutes. Manual disconnect waits for the monitor to finish before closing all handles.

## Security

A generated `.vpn` file or QR contains private connection material and the scoped capability needed for future unique exports. Treat it as a secret and share it only with its intended recipient. Provisioning uses HTTPS with an exact SHA-256 certificate pin; bearer tokens are stored as hashes on the server.

The source tree, APKs, and public releases must never contain live profiles, tokens, private keys, PSKs, SSH credentials, or deployment addresses. Server configuration files should use mode `0600`.
