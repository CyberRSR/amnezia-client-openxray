package main

import (
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

// udpRelay carries the exit layer's datagrams from a loopback socket into the
// entry netstack. The exit device sees a plain 127.0.0.1 peer endpoint, so the
// whole exit tunnel travels inside the entry tunnel.
type udpRelay struct {
	listener  *net.UDPConn
	upstream  net.Conn
	healthy   atomic.Bool
	clientMu  sync.RWMutex
	client    *net.UDPAddr
	closeOnce sync.Once
}

func startUDPRelay(tnet *netstack.Net, relayHost string, relayPort int32, logger *device.Logger) (*udpRelay, error) {
	if strings.TrimSpace(relayHost) == "" || relayPort < 1 || relayPort > 65535 {
		return nil, fmt.Errorf("invalid relay endpoint %q:%d", relayHost, relayPort)
	}

	listener, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return nil, fmt.Errorf("listen on local UDP relay: %w", err)
	}

	upstream, err := tnet.Dial("udp", net.JoinHostPort(relayHost, strconv.Itoa(int(relayPort))))
	if err != nil {
		listener.Close()
		return nil, fmt.Errorf("dial relay through netstack: %w", err)
	}

	relay := &udpRelay{listener: listener, upstream: upstream}
	relay.healthy.Store(true)
	go relay.copyToNetstack(logger)
	go relay.copyFromNetstack(logger)
	return relay, nil
}

func (relay *udpRelay) copyToNetstack(logger *device.Logger) {
	buffer := make([]byte, 65535)
	for relay.healthy.Load() {
		n, client, err := relay.listener.ReadFromUDP(buffer)
		if err != nil {
			relay.fail(logger, "local UDP relay read", err)
			return
		}
		relay.clientMu.Lock()
		relay.client = &net.UDPAddr{IP: append(net.IP(nil), client.IP...), Port: client.Port, Zone: client.Zone}
		relay.clientMu.Unlock()
		if _, err := relay.upstream.Write(buffer[:n]); err != nil {
			relay.fail(logger, "netstack UDP relay write", err)
			return
		}
	}
}

func (relay *udpRelay) copyFromNetstack(logger *device.Logger) {
	buffer := make([]byte, 65535)
	for relay.healthy.Load() {
		n, err := relay.upstream.Read(buffer)
		if err != nil {
			relay.fail(logger, "netstack UDP relay read", err)
			return
		}
		relay.clientMu.RLock()
		client := relay.client
		relay.clientMu.RUnlock()
		if client == nil {
			continue
		}
		if _, err := relay.listener.WriteToUDP(buffer[:n], client); err != nil {
			relay.fail(logger, "local UDP relay write", err)
			return
		}
	}
}

func (relay *udpRelay) fail(logger *device.Logger, operation string, err error) {
	if relay.healthy.Swap(false) {
		logger.Errorf("%s: %v", operation, err)
		relay.closeSockets()
	}
}

func (relay *udpRelay) closeSockets() {
	relay.closeOnce.Do(func() {
		relay.listener.Close()
		relay.upstream.Close()
	})
}

func (relay *udpRelay) Close() {
	relay.healthy.Store(false)
	relay.closeSockets()
}

func (relay *udpRelay) Port() int32 {
	if address, ok := relay.listener.LocalAddr().(*net.UDPAddr); ok {
		return int32(address.Port)
	}
	return -1
}
