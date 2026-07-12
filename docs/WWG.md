# WWG on Android

WWG is a chained Android protocol composed of two AmneziaWG v2 tunnels:

```text
Android app -> entry AWG v2 netstack -> local UDP relay -> exit AWG v2 -> internet
```

The entry tunnel uses the official AmneziaWG Go netstack and does not create an Android `VpnService` interface. It exposes a loopback UDP relay. The exit tunnel is the only tunnel that establishes the system VPN interface and sends its peer traffic to that relay. Physical sockets belonging to the entry tunnel are protected with `VpnService.protect` so they do not loop back into the exit tunnel.

## Import and stored profile

Select the entry AmneziaWG `.conf` first and the exit `.conf` second. Both files must declare `protocol_version=2` and contain the required AWG v2 J/S/H/I parameters. Other versions or incomplete profiles are rejected before the connection lifecycle starts.

The persisted container schema is:

```text
containers[].container = "amnezia-wwg"
containers[].wwg.underlay_awg = AwgProtocolConfig
containers[].wwg.overlay_awg  = AwgProtocolConfig
```

The Android runtime receives:

```text
runtime.protocol = "WWG"
runtime.awg_underlay_config_data = <entry client config>
runtime.awg_overlay_config_data  = <exit client config>
```

Portable `.vpn` export/import serializes both layers without converting them to a single native `.conf`.

## Lifecycle and recovery

One coroutine guarded by a mutex and generation counter owns the whole chain. A restart always closes `overlay -> relay -> underlay`, then starts `underlay -> relay -> overlay`. The connection state remains `RECONNECTING` during recovery, and no second worker or relay can be started in parallel.

Relay health is checked every second. An HTTP probe is run every five seconds through the Android network that explicitly has `TRANSPORT_VPN`; a second endpoint is used as fallback. A fatal relay failure restarts immediately, while two consecutive failed probe rounds restart the full chain. A transient start failure is retried after five seconds and then every ten seconds without a retry limit. Manual disconnect cancels the monitor and restart jobs and closes all handles. Initial format errors remain fatal.

## Security

The repository, APKs, and releases must not contain real server profiles, private keys, pre-shared keys, SSH credentials, or deployment IP addresses. Keep deployment configs in a protected temporary directory and store server-side configs with mode `0600`.
