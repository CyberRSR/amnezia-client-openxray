# OXray Leak Probe

Small Android probe app for checking whether ordinary app traffic goes through OXray.

It logs to `OxrayLeakProbe`:

- IPv4 public IP via `https://api.ipify.org`.
- IPv6 public IP via `https://api6.ipify.org`.
- Dual-stack public IP via `https://api64.ipify.org`.
- System resolver lookup for a random domain under `dns_suffix`.
- DoH lookup for the same random domain.
- Optional DoT lookup to a configured host and port.
- Optional HTTP hit to a configured VPS endpoint.
- Optional arbitrary HTTPS URL probe, with configurable parallel request count.

Example launch:

```bash
adb shell am start -n org.amnezia.oxrayprobe/.MainActivity \
  --es dns_suffix leak.example.com \
  --es probe_base_url https://vps.example.com/oxray-probe \
  --es test_url https://example.com \
  --ei test_parallel 4 \
  --es doh_url https://vps.example.com/dns-query \
  --es dot_host vps.example.com \
  --ei dot_port 853 \
  --ei rounds 120
```

Watch logs:

```bash
adb logcat -s OxrayLeakProbe
```
