#!/bin/sh
set -eu

CONFIG_FILE="${AWG_CONFIG_FILE:-/etc/amneziawg/awg0.conf}"
HPK_FILE="${AWG_HPK_FILE:-/run/secrets/awg3_hpk.hex}"
INTERFACE_NAME="${AWG_INTERFACE:-awg0}"
VPN_SUBNET="${AWG_VPN_SUBNET:?AWG_VPN_SUBNET is required}"
OUT_INTERFACE="${AWG_OUT_INTERFACE:-eth0}"
UAPI_SOCKET="/var/run/amneziawg/${INTERFACE_NAME}.sock"

cleanup() {
    awg-quick down "${CONFIG_FILE}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

test -f "${CONFIG_FILE}"
test -f "${HPK_FILE}"
test "$(wc -c < "${HPK_FILE}")" -ge 64

cleanup
awg-quick up "${CONFIG_FILE}"

tries=0
while [ ! -S "${UAPI_SOCKET}" ]; do
    tries=$((tries + 1))
    if [ "${tries}" -ge 50 ]; then
        echo "AmneziaWG UAPI socket did not appear: ${UAPI_SOCKET}" >&2
        exit 1
    fi
    sleep 0.1
done

awg-uapi-config \
    -socket "${UAPI_SOCKET}" \
    -header-protection-key "$(tr -d '\r\n ' < "${HPK_FILE}")" \
    -content-padding-addition "${AWG_CONTENT_PADDING_ADDITION:-}" \
    -rekey-after-time "${AWG_REKEY_AFTER_TIME:-}" \
    -rekey-timeout "${AWG_REKEY_TIMEOUT:-}" \
    -reject-after-time "${AWG_REJECT_AFTER_TIME:-}" \
    -keepalive-timeout "${AWG_KEEPALIVE_TIMEOUT:-}" \
    -max-handshake-attempts "${AWG_MAX_HANDSHAKE_ATTEMPTS:-}"

iptables -C INPUT -i "${INTERFACE_NAME}" -j ACCEPT 2>/dev/null \
    || iptables -A INPUT -i "${INTERFACE_NAME}" -j ACCEPT
iptables -C FORWARD -i "${INTERFACE_NAME}" -o "${OUT_INTERFACE}" -s "${VPN_SUBNET}" -j ACCEPT 2>/dev/null \
    || iptables -A FORWARD -i "${INTERFACE_NAME}" -o "${OUT_INTERFACE}" -s "${VPN_SUBNET}" -j ACCEPT
iptables -C FORWARD -o "${INTERFACE_NAME}" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null \
    || iptables -A FORWARD -o "${INTERFACE_NAME}" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -t nat -C POSTROUTING -s "${VPN_SUBNET}" -o "${OUT_INTERFACE}" -j MASQUERADE 2>/dev/null \
    || iptables -t nat -A POSTROUTING -s "${VPN_SUBNET}" -o "${OUT_INTERFACE}" -j MASQUERADE

while :; do
    sleep 3600 &
    wait "$!"
done
