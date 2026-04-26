# Amnezia Client OXray Branch

This branch contains an Amnezia Client fork with `OXray` support.

## What OXray Is

In this project, `OXray` is a composite Amnezia profile that combines Xray and OpenVPN in one Android VPN session.

The current Android implementation uses this traffic chain:

`apps -> Android VpnService TUN -> tun2socks -> Xray -> local OpenVPN userspace SOCKS underlay -> OpenVPN server -> Xray server -> internet resource`

Only one Android `VpnService` is created by the app. OpenVPN does not create a second Android VPN interface in OXray mode. Instead, OpenVPN runs as a userspace underlay transport for Xray outbound connections.

This mode is useful when you need to:

- keep using an existing `.ovpn` client profile
- send application traffic through Xray first
- carry Xray outbound connections through OpenVPN
- keep the `OpenVPN + Xray` pair as one profile inside Amnezia Client
- avoid two competing Android VPN services

## Current Limitations

- The first stable Android implementation supports TCP OpenVPN profiles only.
- UDP OpenVPN profiles are rejected with a clear error.
- The local SOCKS endpoint is internal to the app and is used only between Xray and the OpenVPN userspace backend.
- The local SOCKS endpoint is not exposed to the provider or to remote networks.

## What This Branch Adds

- a new `OXray` protocol type
- import from two files: `OpenVPN (.ovpn)` and `Xray (.json/.txt/.conf)`
- native OXray import and export format
- a dedicated OXray settings page
- native OXray JSON and QR export
- Android integration for the single-VPN Xray-over-OpenVPN-underlay mode
- a userspace OpenVPN TCP underlay backend for Android OXray connections

## How To Load An OXray Configuration

Two main scenarios are supported.

### 1. Import From OpenVPN And Xray Files

Use this option if you already have:

- an `OpenVPN` client config in `.ovpn` format
- an `Xray` client config in `.json`, `.txt`, or `.conf` format

Steps in the app:

1. Open the add-connection screen.
2. Select `OXray (OpenVPN + XRay)`.
3. Choose the OpenVPN file.
4. Choose the Xray file.
5. Review the generated profile and save it.

The client combines both files into one Amnezia profile with the `amnezia-oxray` container.

### 2. Import From Native OXray JSON

Use this option when the profile was previously exported from this branch.

Native format marker:

```json
{
  "format": "amnezia-oxray-native",
  "version": 1
}
```

Steps in the app:

1. Open `File with connection settings`.
2. Select the exported native `.json` file.
3. Review the profile and import it.

The same profile can also be shared through QR export.

## Native OXray Format

The native export is a JSON document with the main fields below:

```json
{
  "format": "amnezia-oxray-native",
  "version": 1,
  "description": "My OXray profile",
  "openvpnConfig": "<full .ovpn text>",
  "xrayConfig": {
    "outbounds": []
  },
  "useCustomDns": true,
  "dns1": "1.1.1.1",
  "dns2": "8.8.8.8"
}
```

Field meaning:

- `openvpnConfig`: original OpenVPN client config text
- `xrayConfig`: full Xray client JSON config
- `description`: profile name in the app
- `useCustomDns`, `dns1`, `dns2`: optional DNS settings for the profile

## OXray Settings

The settings page is split into `OpenVPN`, `XRay`, and `DNS` sections.

### OpenVPN

- `VPN address subnet`: internal OpenVPN layer subnet
- `Network protocol`: selects the OpenVPN transport protocol; Android OXray userspace mode currently supports `tcp`
- `OpenVPN port`: rewrites the `remote ... <port>` directive in the client config
- `Auto-negotiate encryption`: controls OpenVPN encryption negotiation and `ncp-disable` behavior
- `Hash`: sets the `auth` value
- `Cipher`: sets the `cipher` value
- `TLS auth`: enables or removes `tls-auth` in the resulting client config
- `Block DNS requests outside of VPN`: enables or disables `block-outside-dns`
- `Additional client configuration commands`: adds a managed custom block to the resulting OpenVPN client config
- `Additional server configuration commands`: kept in the profile for OpenVPN server setup scenarios

### Xray

- `Disguised as traffic from`: updates `streamSettings.realitySettings.serverName`
- `XRay port`: updates the port of the first outbound node in the Xray config

In Android OXray mode, the main Xray outbound is configured to use a local SOCKS outbound tagged as the OpenVPN underlay. The old system-interface `sendThrough` mode is not used.

### DNS

- `Use custom DNS`: enables custom DNS for the profile
- `Primary DNS`: primary DNS server
- `Secondary DNS`: fallback DNS server

## Export

This branch supports:

- native OXray JSON export
- QR export for native OXray profiles

Export is available from the sharing page and from the OXray settings page.

Default file name:

- `amnezia_for_oxray_native.json`

## Technical Details

- The OXray profile is stored as a composite container with `openvpn` and `xray` blocks.
- Android OXray mode creates one app-owned `VpnService` interface.
- The TUN file descriptor is passed to tun2socks.
- Xray is started without creating a second VPN interface.
- The OpenVPN backend runs in userspace and exposes an internal local SOCKS5 endpoint.
- Xray gets an additional SOCKS outbound that points to that local OpenVPN underlay.
- The main Xray outbound uses `proxySettings.transportLayer=true` to route its transport through the OpenVPN underlay.
- OpenVPN transport sockets are protected with `VpnService.protect()` so they do not loop back into the upper VPN TUN.
- Direct Xray socket protection is intentionally not used in OXray mode because Xray must go through the local OpenVPN underlay.

## Getting The Sources

After cloning the repository, initialize submodules:

```bash
git submodule update --init --recursive
```

This branch is based on the Amnezia Client codebase. Use the upstream project documentation for the general build process and project structure. This README describes the OXray behavior in this branch.

## Security

Do not commit real user configs, exported QR payloads, private keys, certificates, test accounts, logs, APK files, or local diagnostic output.

This branch keeps local artifacts out of commits, including:

- temporary `.pem` files
- local test `.ovpn` files
- local build logs
- local build and export directories
- Android logcat captures
- generated APK files

## Base Project

- upstream: [amnezia-vpn/amnezia-client](https://github.com/amnezia-vpn/amnezia-client)
- this branch: a fork for importing, editing, exporting, and running the `Xray over OpenVPN userspace underlay` chain inside Amnezia Client
