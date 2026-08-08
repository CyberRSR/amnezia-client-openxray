# WWG unique-peer export provisioner

This service is the server-side half of safe WWG file/QR sharing. The app
generates a new X25519 keypair and preshared key for each WWG layer. The
provisioner accepts only the public key and PSK, allocates a free `/32`, writes
one fixed `[Peer]` block and applies it to one preconfigured container.

It is deliberately not a remote shell:

- a bearer capability maps to exactly one configured container and subnet;
- the stored server configuration contains only SHA-256 of that capability;
- HTTPS uses a private certificate whose exact SHA-256 digest is pinned in the
  WWG profile;
- client private keys never leave the exporting device;
- request bodies are bounded, keys and addresses are validated, creation is
  rate-limited, and the pool has an explicit maximum;
- rollback is limited to a freshly provisioned peer by a short-lived HMAC
  receipt;
- existing hand-created peers cannot be removed through the API.

Profiles whose persistent configuration is bind-mounted from the host use
`config_path`. A legacy container that owns its configuration internally can
instead use `container_config_path`; the service updates that file through a
fixed `docker exec` operation and never recreates the container. Exactly one
of these fields is required. If a host configuration lives outside the paths
listed in the unit's `ReadWritePaths`, add that exact directory before enabling
the service.

## Deployment outline

1. Copy `provisioner.py` to `/opt/wwg-peer-provisioner/` and create a root-only
   `config.json` based on `config.example.json`.
2. Generate independent random capability tokens for every v2/v3 profile.
   Put only `sha256(token)` in `config.json`; the raw token goes into the
   matching client profile.
3. Generate a private TLS key/certificate and a 32-byte rollback secret with
   mode `0600`. Put the lowercase SHA-256 certificate digest in the profile.
4. Verify the configured persistent `awg0.conf` path and live public key with:

   ```sh
   python3 provisioner.py --config config.json --check
   ```

5. Install `wwg-peer-provisioner.service`, restrict the selected TCP port in
   the host firewall as appropriate, enable the unit, then test `/healthz` and
   one create/rollback transaction before distributing profiles.

The provisioner rewrites the existing configuration inode so the running
read-only Docker bind mount sees newly appended peers. Keep the configuration,
TLS private key, capability tokens, rollback secret and generated profiles out
of Git and release assets.
